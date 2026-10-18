package com.cleo.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ProviderSettings(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
)

@Serializable
data class Role(val id: String, val name: String, val emoji: String = "\uD83D\uDC3E", val persona: String = "")

@Serializable
data class ChatMessage(val role: String, val text: String, val at: Long = 0)

@Serializable
data class AppData(
    val provider: ProviderSettings = ProviderSettings(),
    val roles: List<Role> = emptyList(),
    val conversations: Map<String, List<ChatMessage>> = emptyMap(),
    val activeRoleId: String? = null,
)

/** 本地 JSON 存储：重启后能恢复。和 Android 的数据格式无关，互不影响。 */
object DesktopStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** 配置放用户数据目录（Windows 上是 %APPDATA%\CleosDesktop）。 */
    private val defaultFile: File = run {
        val appData = System.getenv("APPDATA")
        val dir = if (!appData.isNullOrBlank()) File(appData, "CleosDesktop")
        else File(System.getProperty("user.home"), ".cleos-desktop")
        File(dir, "data.json")
    }

    /** 可以改：自检写到构建目录里，不碰用户数据。 */
    var file: File = defaultFile

    fun load(): AppData = runCatching {
        if (!file.exists()) AppData()
        else json.decodeFromString(AppData.serializer(), file.readText())
    }.getOrElse { AppData() }

    fun save(data: AppData) {
        // 目录可能还不存在（第一次运行）：用 Files 明确创建，失败会抛出来而不是静默写不进去。
        val parent = file.parentFile ?: File(System.getProperty("user.home"))
        java.nio.file.Files.createDirectories(parent.toPath())
        val tmp = File(parent, "data.json.tmp")
        tmp.writeText(json.encodeToString(AppData.serializer(), data))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
