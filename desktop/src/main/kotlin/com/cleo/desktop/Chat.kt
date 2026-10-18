package com.cleo.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 最小聊天客户端：OpenAI 兼容的 /chat/completions，流式读取 SSE。
 * 只做第一阶段需要的：流式、停止、错误原因。桌面端独立实现，不依赖 Android 的 ChatClient。
 */
class DesktopChatClient {
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var call: Call? = null

    /** 停止当前请求（界面上的「停止」按钮）。 */
    fun cancel() {
        call?.cancel()
    }

    suspend fun stream(
        provider: ProviderSettings,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (provider.apiKey.isBlank()) throw IOException("还没填 API Key")
        if (provider.model.isBlank()) throw IOException("还没填模型名")
        val messages = buildJsonArray {
            for (m in history) {
                add(buildJsonObject {
                    put("role", if (m.role == "user") "user" else "assistant")
                    put("content", m.text)
                })
            }
        }
        val body = buildJsonObject {
            put("model", provider.model)
            put("messages", messages)
            put("stream", true)
        }
        val request = Request.Builder()
            .url(provider.baseUrl.trim().trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer " + provider.apiKey.trim())
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val c = http.newCall(request)
        call = c
        try {
            val response = c.execute()
            if (!response.isSuccessful) {
                val detail = runCatching { response.body.string().take(300) }.getOrDefault("")
                throw IOException("HTTP " + response.code + "：" + detail)
            }
            val answer = StringBuilder()
            response.body.source().use { source ->
                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val delta = choice?.get("delta") as? JsonObject
                    val text = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        answer.append(text)
                        withContext(Dispatchers.Main) { onDelta(text) }
                    }
                }
            }
            answer.toString()
        } finally {
            call = null
        }
    }
}
