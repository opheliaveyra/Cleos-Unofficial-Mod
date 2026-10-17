package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 导入预览里的一条服务。 */
data class McpImportCandidate(
    /** JSON 里的 key（一般就是服务名）。 */
    val key: String,
    val name: String,
    /** [McpServer.TRANSPORT_HTTP] / [McpServer.TRANSPORT_SSE] / "stdio"。 */
    val transport: String,
    val url: String,
    val token: String,
    val headers: List<McpHeader>,
    /** 不能用的原因（stdio、没地址、地址不对……）；null = 能用。 */
    val problem: String?,
) {
    val usable: Boolean get() = problem == null

    val transportLabel: String
        get() = when (transport) {
            McpServer.TRANSPORT_SSE -> "老式 SSE"
            "stdio" -> "本地进程（stdio）"
            else -> "Streamable HTTP"
        }
}

/** 一次解析的结果。 */
data class McpImportResult(val candidates: List<McpImportCandidate>, val error: String? = null)

/**
 * 从常见的 MCP 配置里读出服务 —— **只解析，不保存**（预览和勾选由界面做）。
 *
 * 认得的形状：
 * - `{"mcpServers": { "名字": {...} }}`（Claude Desktop 和大多数客户端）
 * - `{"servers": {...}}`、`{"mcp": {...}}`；直接就是 `{ "名字": {...} }` 也认
 *
 * 每条：
 * - 远程：`url`（`serverUrl` / `endpoint` / `baseUrl` 也认）；`type` 或 `transport` 写了 `sse` 就走老式 SSE，
 *   地址里带 `/sse` 也按老式 SSE 认（很多服务的默认写法）
 * - 请求头：`headers` 可以是对象（名字 → 值）或数组（name/value）
 * - 鉴权：`token` / `apiKey`，或者 headers 里自己写 Authorization
 * - 本地：`command` + `args` + `env` —— **Android 上起不了子进程**，标成当前环境不支持
 */
object McpImport {
    fun parse(text: String): McpImportResult {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return McpImportResult(emptyList(), "这段 JSON 读不出来 —— 检查一下引号、逗号，或者是不是贴少了半截")
        val servers = (root["mcpServers"] as? JsonObject)
            ?: (root["servers"] as? JsonObject)
            ?: (root["mcp"] as? JsonObject)
            ?: root
        if (servers.isEmpty()) return McpImportResult(emptyList(), "里面没有服务 —— 需要类似 {\"mcpServers\": {…}} 的结构")
        val out = servers.map { (key, element) -> candidate(key, element as? JsonObject) }
        if (out.isEmpty()) return McpImportResult(emptyList(), "里面没有能读出来的服务")
        return McpImportResult(out)
    }

    private fun candidate(key: String, o: JsonObject?): McpImportCandidate {
        if (o == null) {
            return McpImportCandidate(key, key, McpServer.TRANSPORT_HTTP, "", "", emptyList(), "这一项不是对象，读不出来")
        }
        val url = (str(o, "url") ?: str(o, "serverUrl") ?: str(o, "endpoint") ?: str(o, "baseUrl")).orEmpty().trim()
        val command = str(o, "command")
        val fallback = str(o, "name")?.trim()?.takeIf { it.isNotEmpty() } ?: key
        if (url.isBlank() && !command.isNullOrBlank()) {
            return McpImportCandidate(
                key = key, name = fallback, transport = "stdio", url = "", token = "", headers = emptyList(),
                problem = "这是一个本地进程（stdio）配置：要在设备上起子进程，Android 应用里跑不了 —— 当前环境不支持",
            )
        }
        if (url.isBlank()) {
            return McpImportCandidate(key, fallback, McpServer.TRANSPORT_HTTP, "", "", emptyList(), "没写地址（url）")
        }
        val declared = (str(o, "type") ?: str(o, "transport")).orEmpty().lowercase()
        val transport = when {
            "sse" in declared -> McpServer.TRANSPORT_SSE
            "http" in declared -> McpServer.TRANSPORT_HTTP
            url.contains("/sse") -> McpServer.TRANSPORT_SSE
            else -> McpServer.TRANSPORT_HTTP
        }
        val problem = if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            "地址要是 http:// 或 https:// 开头"
        } else null
        return McpImportCandidate(
            key = key,
            name = fallback,
            transport = transport,
            url = url,
            token = (str(o, "token") ?: str(o, "apiKey") ?: str(o, "api_key")).orEmpty().trim(),
            headers = headersOf(o),
            problem = problem,
        )
    }

    private fun headersOf(o: JsonObject): List<McpHeader> {
        val raw = o["headers"] ?: o["header"] ?: return emptyList()
        return when (raw) {
            is JsonObject -> raw.mapNotNull { (name, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                McpHeader(name.trim(), text)
            }
            is JsonArray -> raw.mapNotNull { element ->
                val h = element as? JsonObject ?: return@mapNotNull null
                val name = str(h, "name")?.trim() ?: return@mapNotNull null
                McpHeader(name, str(h, "value").orEmpty())
            }
            is JsonPrimitive -> listOfNotNull(raw.contentOrNull?.takeIf { it.isNotBlank() }?.let(::parseHeaderLine))
            else -> emptyList()
        }.filterNot { it.isBlank }
    }

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.contentOrNull
}
