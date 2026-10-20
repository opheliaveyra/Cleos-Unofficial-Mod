package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.InstallStep
import com.cleo.cleos.UpdateResult
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.launch

/**
 * 更新与版本。
 *
 * 只用二改版仓库的 Releases：正式版、有 .apk 附件、版本号比现在大，才叫「有更新」。
 * 没有 Release 就说暂无更新，不拿提交记录或临时构建冒充。
 */
@Composable
internal fun UpdatePage(
    /// 设置里那个「启动时自动检查」的开关值。
    autoCheck: Boolean,
    onAutoCheck: (Boolean) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val checking by c.updates.checking.collectAsStateWithLifecycle()
    val feedback by c.updates.feedback.collectAsStateWithLifecycle()
    val last by c.updates.lastResult.collectAsStateWithLifecycle()
    val install by c.updates.install.collectAsStateWithLifecycle()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()?.let {
            (it.versionName ?: "") + "（" + it.longVersionCode + "）"
        } ?: "未知"
    }
    var remembered by remember { mutableStateOf(c.updates.rememberedResult()) }
    val shown: UpdateResult? = last ?: remembered

    Section("当前版本") {
        Text("Cleos 二改版 " + version, color = palette.content, fontSize = 15.sp)
        Text(
            "更新来源只有二改版仓库（github.com/opheliaveyra/Cleos-Unofficial-Mod）的正式 Release。" +
                "原作者的版本和以前的蓝奏云链接都不会再出现在这里——装上去会把这些改动盖掉。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("历史版本", selected = false) {
                c.updates.openPage("https://github.com/opheliaveyra/Cleos-Unofficial-Mod/releases")
            }
        }
    }

    Section("检查更新") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (checking) "正在检查…" else "检查更新", selected = false) {
                if (!checking) scope.launch { c.updates.check(manual = true) }
            }
            Chip(if (autoCheck) "启动时自动检查：开" else "启动时自动检查：关", selected = autoCheck) {
                onAutoCheck(!autoCheck)
            }
        }
        Text(
            "自动检查只是查一下有没有新版本，不会自己下载、更不会自己装。关掉之后就只能在这里手动点。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp,
        )
        feedback?.let { Text(it, color = palette.content, fontSize = 13.sp) }
    }

    when (val r = shown) {
        is UpdateResult.Available -> {
            val info = r.info
            Section("新版本 " + info.version) {
                if (info.publishedAt.isNotBlank()) {
                    Text("发布于 " + info.publishedAt.take(10), color = palette.contentSecondary, fontSize = 12.sp)
                }
                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        color = palette.contentSecondary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    )
                }
                if (info.apkUrl == null) {
                    Text(
                        "这个版本没有附带安装包，只能去 Release 页面看看。",
                        color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    Chip("打开 Release 页面", selected = false) { c.updates.openPage(info.pageUrl) }
                } else {
                    when (val s = install) {
                        InstallStep.Idle -> {
                            Text(
                                "安装包 " + String.format("%.1f", info.apkSize / 1024.0 / 1024.0) + " MB" +
                                    if (info.sha256 != null) " · 带 SHA-256 校验" else "",
                                color = palette.contentSecondary, fontSize = 12.sp,
                            )
                            Text(
                                "下载完会先校验大小、校验值、包名和签名，都对得上才让你点安装；覆盖安装，不用先卸载。",
                                color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                            )
                            Chip("下载更新", selected = false) { scope.launch { c.updates.downloadAndVerify(info) } }
                        }
                        is InstallStep.Downloading -> {
                            val pct = if (s.total > 0) (s.done * 100 / s.total) else 0
                            val mb = String.format("%.1f", s.done / 1024.0 / 1024.0)
                            val all = if (s.total > 0) String.format("%.1f", s.total / 1024.0 / 1024.0) else "?"
                            Text("正在下载 " + pct + "%（" + mb + " / " + all + " MB）", color = palette.content, fontSize = 13.sp)
                        }
                        InstallStep.Verifying -> Text("正在校验安装包…", color = palette.content, fontSize = 13.sp)
                        InstallStep.Ready -> {
                            Text("下载好了，校验通过。", color = palette.content, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("安装", selected = true) { c.updates.install() }
                                Chip("取消", selected = false) { c.updates.resetInstall() }
                            }
                        }
                        is InstallStep.Failed -> {
                            Text(s.reason, color = palette.error, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("重试", selected = false) { scope.launch { c.updates.downloadAndVerify(info) } }
                                Chip("打开 Release 页面", selected = false) { c.updates.openPage(info.pageUrl) }
                            }
                        }
                    }
                }
            }
        }
        UpdateResult.Current -> Section("检查结果") {
            Text("已经是最新版本，不用更新。", color = palette.content, fontSize = 13.sp)
        }
        UpdateResult.NoRelease -> Section("检查结果") {
            Text(
                "二改版的仓库还没有正式发布，暂无可用更新。" +
                    "等有正式 Release（带 .apk 的那种）之后，这里才会显示。",
                color = palette.content, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Chip("打开 Release 页面", selected = false) {
                c.updates.openPage("https://github.com/opheliaveyra/Cleos-Unofficial-Mod/releases")
            }
        }
        is UpdateResult.Failed -> Section("检查结果") {
            Text(r.reason, color = palette.error, fontSize = 13.sp, lineHeight = 19.sp)
            Chip("再试一次", selected = false) { scope.launch { c.updates.check(manual = true) } }
        }
        null -> Section("检查结果") {
            Text("还没检查过。点上面的「检查更新」看看有没有新版本。", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }

    // 进页面时不自动查（免得每次进设置都打一次接口），要不要查由上面的按钮决定。
    LaunchedEffect(Unit) {
        if (autoCheck && last == null && !checking) c.updates.check(manual = false)
    }
}
