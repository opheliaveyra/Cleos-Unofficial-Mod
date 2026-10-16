package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条一起发出去的视频。
 *
 * **只存名字，视频本体在 [VideoStore] 的目录里** —— 和 [MessageImage] 一个路子：
 * 一直握着选择器的 `content://` 授权不可靠，那种授权会过期，人家也可能把视频删了。
 */
@Serializable
data class MessageVideo(
    val file: String,
    /** 多长（毫秒）；读不出来是 0。用来在聊天里显示 `0:12`。 */
    val durationMs: Long = 0,
    /** 多大（字节）；读不出来是 0。 */
    val bytes: Long = 0,
)

/** 消息上那份视频名单的编解码。存坏了就当没有 —— 不能让一段坏 JSON 把整条消息弄没。 */
object MessageVideos {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String?): List<MessageVideo> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<MessageVideo>>(raw) }.getOrDefault(emptyList())
    }

    fun encode(videos: List<MessageVideo>): String? {
        if (videos.isEmpty()) return null
        return runCatching { json.encodeToString(videos) }.getOrNull()
    }
}
