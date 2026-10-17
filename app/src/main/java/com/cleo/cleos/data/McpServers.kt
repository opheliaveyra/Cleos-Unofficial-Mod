package com.cleo.cleos.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一条自定义请求头（名字 + 值）。可以有好几条。 */
@Serializable
data class McpHeader(val name: String = "", val value: String = "") {
    val isBlank: Boolean get() = name.isBlank()
}

/**
 * 一个 MCP 服务。
 *
 * 连接方式：
 * - [TRANSPORT_HTTP]：**Streamable HTTP**（现在的主流，POST 到地址本身）
 * - [TRANSPORT_SSE]：**老式 HTTP+SSE**（GET 一个事件流、POST 到流里给的地址）
 *
 * Token 走 `Authorization: Bearer …`；**如果自定义请求头里已经有 Authorization，就不再发 Bearer**，
 * 免得一条请求里两个 Authorization（服务端只认第一个，结果多半是 401 而不是报错）。
 */
@Serializable
data class McpServer(
    val id: String,
    val name: String = "",
    val url: String = "",
    /** 以 `Authorization: Bearer …` 发出去；粘贴时带不带 "Bearer " 都行。 */
    val token: String = "",
    /** 自定义请求头，可以多条。和上面重复时以这里为准。 */
    val headers: List<McpHeader> = emptyList(),
    /** 老版本的单条请求头（"Name: value"）；读进来会并进 [headers]，之后不再写它。 */
    val header: String = "",
    val transport: String = TRANSPORT_HTTP,
    val enabled: Boolean = true,
    val askFirst: Boolean = true,
    val allowed: Set<String> = emptySet(),
    val denied: Set<String> = emptySet(),
) {
    /** 能用的请求头：去掉空行，旧字段并进来。 */
    val effectiveHeaders: List<McpHeader>
        get() = (headers + listOfNotNull(header.takeIf { it.isNotBlank() }?.let(::parseHeaderLine)))
            .filterNot { it.isBlank }
            .map { McpHeader(it.name.trim(), it.value.trim()) }

    /** 自定义头里已经有 Authorization 了吗（大小写不敏感）。 */
    val hasOwnAuthorization: Boolean
        get() = effectiveHeaders.any { it.name.equals("Authorization", ignoreCase = true) }

    /** What the connection depends on: a change here means a new session and a new tool list. */
    val connection: String
        get() = listOf(url.trim(), transport, token.trim(), effectiveHeaders.joinToString(";") { it.name + "=" + it.value })
            .joinToString("\n")

    companion object {
        const val TRANSPORT_HTTP = "http"
        const val TRANSPORT_SSE = "sse"
    }
}


/** The MCP services, as one encrypted list. */
class McpServers(private val secrets: SecretStore) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Mutex()

    val all: Flow<List<McpServer>> = secrets.secret(KEY).map(::decode)

    suspend fun current(): List<McpServer> = all.first()

    suspend fun get(id: String): McpServer? = current().firstOrNull { it.id == id }

    /** Adds [server], or replaces the one with its id. */
    suspend fun save(server: McpServer) = edit { list ->
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server
    }

    /** 一次存一批（导入用）；已存在的 id 覆盖，其余追加。 */
    suspend fun saveAll(servers: List<McpServer>) = edit { list ->
        val byId = list.associateBy { it.id }.toMutableMap()
        servers.forEach { byId[it.id] = it }
        list.map { byId.getValue(it.id) } + servers.filter { s -> list.none { it.id == s.id } }
    }

    /** 整体替换（恢复备份时用）。 */
    suspend fun replaceAll(servers: List<McpServer>) = edit { servers }

    suspend fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, on: Boolean) = edit { list -> list.map { if (it.id == id) it.copy(enabled = on) else it } }

    /** The person said to always allow [tool] of service [id]: no more asking for it. */
    suspend fun allow(id: String, tool: String) = edit { list ->
        list.map { if (it.id == id) it.copy(allowed = it.allowed + tool, denied = it.denied - tool) else it }
    }

    /** The person said never to use [tool] of service [id]: refuse it outright, don't ask again. */
    suspend fun disallow(id: String, tool: String) = edit { list ->
        list.map { if (it.id == id) it.copy(denied = it.denied + tool, allowed = it.allowed - tool) else it }
    }

    private suspend fun edit(change: (List<McpServer>) -> List<McpServer>) = lock.withLock {
        secrets.setSecret(KEY, json.encodeToString(change(current())))
    }

    private fun decode(raw: String?): List<McpServer> =
        raw?.let { runCatching { json.decodeFromString<List<McpServer>>(it) }.getOrNull() }
            ?.map { s -> if (s.header.isNotBlank()) s.copy(header = "") else s }
            ?: emptyList()

    private companion object {
        const val KEY = "mcp_servers"
    }
}

/** "Name: value" 拆成一条请求头；拆不开就是空的。 */
fun parseHeaderLine(line: String): McpHeader {
    val i = line.indexOf(':')
    if (i <= 0) return McpHeader()
    return McpHeader(line.substring(0, i).trim(), line.substring(i + 1).trim())
}
