package com.cleo.cleos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 二改版自己的更新。
 *
 * 源只有一个：`opheliaveyra/Cleos-Unofficial-Mod` 的 GitHub Releases。
 * 原作者的仓库和以前的蓝奏云链接**不再参与** —— 那边给的是原版，装上来会把这些改动盖掉。
 *
 * 只有正式 Release 才算数：Draft、Prerelease 全部跳过；没有 Release 就老实说「暂无可用更新」，
 * 不拿提交记录或临时构建冒充。
 */
internal data class UpdateInfo(
    val version: String,
    val notes: String,
    /** 这个 Release 里那个 .apk 附件；没有就是 null（只能去看页面，不给装）。 */
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long,
    /** 发布方给的 SHA-256（GitHub 的 digest 字段，形如 `sha256:abc…`）。 */
    val sha256: String?,
    /** Release 页面，没有 APK 时给个可从浏览器打开的入口。 */
    val pageUrl: String,
    val publishedAt: String,
)

internal sealed interface UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult
    /** 已是最新。 */
    data object Current : UpdateResult
    /** 仓库还没有任何正式版本。 */
    data object NoRelease : UpdateResult
    /** 网络/限流/数据异常；[reason] 是给人看的一句话。 */
    data class Failed(val reason: String) : UpdateResult
}

internal object UpdateVersions {
    /** 从 tag 或 versionName 里取数字段：`v0.1.94-mod`、`0.1.94` 都认。 */
    fun parts(raw: String): List<Int>? {
        val value = raw.trim().removePrefix("v").removePrefix("V")
        val head = Regex("^([0-9]+(?:\\.[0-9]+)*)").find(value)?.groupValues?.get(1) ?: return null
        val parts = head.split('.').map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.isNotEmpty() && it.size <= 4 }
    }

    /** 逐段比大小，不是字典序：0.1.100 > 0.1.99。 */
    fun newer(latest: String, current: String): Boolean {
        val l = parts(latest) ?: return false
        val c = parts(current) ?: return false
        for (i in 0 until maxOf(l.size, c.size)) {
            val delta = l.getOrElse(i) { 0 }.compareTo(c.getOrElse(i) { 0 })
            if (delta != 0) return delta > 0
        }
        return false
    }

    /**
     * 解析 GitHub Releases 列表里的一个 Release。
     * 返回 null 表示这条不是我们要的（draft / prerelease / tag 读不懂）。
     */
    fun parseRelease(obj: JsonObject, current: String, knownApk: (JsonObject) -> String?, apkSize: (JsonObject) -> Long): Pair<UpdateInfo, Boolean>? {
        val draft = obj["draft"]?.jsonPrimitive?.booleanOrNull ?: false
        val pre = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
        if (draft || pre) return null
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        if (parts(tag) == null) return null
        if (parts(current) == null) return null
        val assets = obj["assets"]?.jsonArray ?: JsonArray(emptyList())
        // 优先找 .apk；有多个就取最后一个（通常是最新打的）
        var apk: JsonObject? = null
        for (a in assets) {
            val o = a.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.endsWith(".apk", ignoreCase = true)) apk = o
        }
        val info = UpdateInfo(
            version = tag.trim().removePrefix("v").removePrefix("V"),
            notes = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(20000),
            apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
            apkName = apk?.get("name")?.jsonPrimitive?.contentOrNull,
            apkSize = apk?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
            sha256 = apk?.get("digest")?.jsonPrimitive?.contentOrNull,
            pageUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull
                ?: "https://github.com/opheliaveyra/Cleos-Unofficial-Mod/releases",
            publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        return info to newer(tag, current)
    }
}

internal object UpdateRules {
    fun shouldCheck(manual: Boolean, now: Long, last: Long): Boolean =
        manual || last <= 0 || now < last || now - last >= 24 * 60 * 60 * 1000L

    fun shouldPrompt(manual: Boolean, version: String, skipped: String?, reminded: String?): Boolean =
        manual || (version != skipped && version != reminded)
}

/** 下载 / 校验 / 安装那一步的结果。 */
internal sealed interface InstallStep {
    data object Idle : InstallStep
    data class Downloading(val done: Long, val total: Long) : InstallStep
    data object Verifying : InstallStep
    data object Ready : InstallStep
    data class Failed(val reason: String) : InstallStep
}

internal class Updates(context: Context, http: OkHttpClient, private val current: String) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val client = http.newBuilder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val dl = http.newBuilder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback = _feedback.asStateFlow()
    /** 上一次检查的结果，给「更新与版本」页一直显示。 */
    private val _lastResult = MutableStateFlow<UpdateResult?>(null)
    val lastResult = _lastResult.asStateFlow()
    private val _install = MutableStateFlow<InstallStep>(InstallStep.Idle)
    val install = _install.asStateFlow()

    suspend fun check(manual: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val last = prefs.getLong("last_check", 0)
            if (!UpdateRules.shouldCheck(manual, now, last)) return
            _checking.value = true
            _feedback.value = null
            prefs.edit().putLong("last_check", now).apply()
            val result = fetch()
            _lastResult.value = result
            prefs.edit().putString("last_result", when (result) {
                is UpdateResult.Available -> "available:" + result.info.version
                UpdateResult.Current -> "current"
                UpdateResult.NoRelease -> "none"
                is UpdateResult.Failed -> "failed:" + result.reason
            }).apply()
            when (result) {
                is UpdateResult.Available -> {
                    val skip = prefs.getString("skip", null)
                    val reminded = prefs.getString("reminded", null)
                    if (UpdateRules.shouldPrompt(manual, result.info.version, skip, reminded)) {
                        _available.value = result.info
                        prefs.edit().putString("reminded", result.info.version).apply()
                    }
                    if (manual) {
                        _feedback.value = if (result.info.apkUrl != null) "发现新版本 ${result.info.version}"
                        else "发现新版本 ${result.info.version}，但这个版本没有附带安装包"
                    }
                }
                UpdateResult.Current -> if (manual) _feedback.value = "当前已是最新版本"
                UpdateResult.NoRelease -> if (manual) _feedback.value = "二改版仓库还没有正式发布，暂无可用更新"
                is UpdateResult.Failed -> if (manual) _feedback.value = result.reason
            }
        } finally { _checking.value = false; mutex.unlock() }
    }

    fun dismiss(skip: Boolean = false) {
        if (skip) _available.value?.let { prefs.edit().putString("skip", it.version).apply() }
        _available.value = null
    }

    /** 上次检查结论，开机时先显示旧的，别让页面空着。 */
    fun rememberedResult(): UpdateResult? {
        val raw = prefs.getString("last_result", null) ?: return null
        return when {
            raw == "current" -> UpdateResult.Current
            raw == "none" -> UpdateResult.NoRelease
            raw.startsWith("failed:") -> UpdateResult.Failed(raw.removePrefix("failed:"))
            raw.startsWith("available:") -> null // 需要重新拉一次才知道还有没有 APK
            else -> null
        }
    }

    private suspend fun fetch(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/opheliaveyra/Cleos-Unofficial-Mod/releases?per_page=20")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Cleos/$current")
                .build()
            client.newCall(request).execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (response.code == 403 || response.code == 429) {
                    return@withContext UpdateResult.Failed("检查太频繁了，GitHub 暂时不让查，过一会儿再试。")
                }
                if (!response.isSuccessful) {
                    return@withContext UpdateResult.Failed("连不上更新服务器（HTTP ${response.code}），检查一下网络再试。")
                }
                val body = response.body ?: return@withContext UpdateResult.Failed("服务器没给内容，稍后再试。")
                val text = body.charStream().use { it.readText() }
                if (text.length > 512000) return@withContext UpdateResult.Failed("更新数据异常，稍后再试。")
                val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull()
                    ?: return@withContext UpdateResult.Failed("更新数据解析失败，稍后再试。")
                if (array.isEmpty()) return@withContext UpdateResult.NoRelease
                var fallback: UpdateInfo? = null
                for (element in array) {
                    val obj = element.jsonObject
                    val parsed = UpdateVersions.parseRelease(obj, current, { null }, { 0L }) ?: continue
                    val (info, newer) = parsed
                    if (newer) return@withContext UpdateResult.Available(info)
                    if (fallback == null) fallback = info
                }
                UpdateResult.Current
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { currentCoroutineContext().ensureActive(); UpdateResult.Failed("网络不可用，检查一下连接再试。") }
    }

    /**
     * 下载并校验。**用户点了下载才走这里**：
     * 下到 cache/updates → 大小、SHA-256、包名、签名都对得上才算通过。
     * 任何一步不对就删文件、说清楚原因，不留半截包。
     */
    suspend fun downloadAndVerify(info: UpdateInfo) {
        val url = info.apkUrl ?: return
        _install.value = InstallStep.Downloading(0, info.apkSize)
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        // 重试前先清掉上一次的半截文件
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, info.apkName?.takeIf { it.endsWith(".apk") } ?: "cleos-update.apk")
        try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).header("User-Agent", "Cleos/$current").build()
                dl.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("下载失败（HTTP ${response.code}）")
                    val body = response.body ?: throw IllegalStateException("下载内容为空")
                    val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                _install.value = InstallStep.Downloading(done, total)
                            }
                        }
                    }
                }
            }
            _install.value = InstallStep.Verifying
            val problem = withContext(Dispatchers.IO) { verify(target, info) }
            if (problem != null) {
                target.delete()
                _install.value = InstallStep.Failed(problem)
            } else {
                _install.value = InstallStep.Ready
            }
        } catch (e: CancellationException) {
            target.delete()
            _install.value = InstallStep.Idle
            throw e
        } catch (e: Exception) {
            target.delete()
            _install.value = InstallStep.Failed("下载中断了：${e.message ?: "网络错误"}。可以重试。")
        }
    }

    /** 校验不通过就返回一句人话；通过了返回 null。 */
    private fun verify(file: File, info: UpdateInfo): String? {
        if (!file.exists() || file.length() < 1024) return "下载下来的文件不完整，重试一次。"
        if (info.apkSize > 0 && file.length() != info.apkSize) {
            return "文件大小对不上（下到 ${file.length()}，应该是 ${info.apkSize}），重试一次。"
        }
        info.sha256?.removePrefix("sha256:")?.trim()?.takeIf { it.length == 64 }?.let { expected ->
            val actual = MessageDigest.getInstance("SHA-256").let { md ->
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actual.equals(expected, ignoreCase = true)) return "校验值对不上，包可能不完整，重试一次。"
        }
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "这个文件不是能安装的 apk，重试一次。"
        if (archive.packageName != app.packageName) return "这个包不是 Cleos（${archive.packageName}），没有安装。"
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val mine = runCatching { pm.getPackageInfo(app.packageName, flags).signingInfo }.getOrNull()
        val theirs = archive.signingInfo
        if (mine != null && theirs != null) {
            val same = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mine.hasMultipleSigners() == theirs.hasMultipleSigners() &&
                mine.apkContentsSigners.contentEquals(theirs.apkContentsSigners)
            else true
            if (!same) return "新包和现在装的这个签名不一样，直接覆盖会装不上（不会让你先卸载）。"
        }
        return null
    }

    /** 交给系统安装器；用户自己点确认，不做静默安装。 */
    fun install() {
        val file = File(app.cacheDir, "updates").listFiles()?.firstOrNull { it.name.endsWith(".apk") } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            _install.value = InstallStep.Failed("需要先允许「安装未知应用」，打开设置里给 Cleos 授权后再回来点安装。")
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }.onFailure { _install.value = InstallStep.Failed("没能打开安装器：${it.message}") }
    }

    fun openPage(url: String) {
        runCatching {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun resetInstall() { _install.value = InstallStep.Idle }
}
