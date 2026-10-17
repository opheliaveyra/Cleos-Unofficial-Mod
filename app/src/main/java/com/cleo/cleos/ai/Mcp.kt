package com.cleo.cleos.ai

import android.util.Log
import com.cleo.cleos.data.McpServer
import com.cleo.cleos.data.McpServers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A tool an MCP service offers, as the service described it. */
data class McpTool(
    val serverId: String,
    val serverName: String,
    /** The service's own name for it: what a call names. */
    val name: String,
    /** What the model calls it: unique across services, in the characters function names allow. */
    val fnName: String,
    /** A name for people: the service's title for it, or its name. */
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    /** The service says it only reads (MCP's readOnlyHint), so it runs without asking. */
    val readOnly: Boolean,
) {
    /** Offered to the model like any other tool; the service's name goes first in the description. */
    val spec: ToolSpec
        get() = ToolSpec(fnName, emptySet(), "用${serverName}的$title", "【$serverName】$description".trim(), inputSchema)
}

/**
 * What the card in the chat asks the person: may the TA do this, here and now?
 *
 * Used for an outside service's tool, and for anything in the TA's own workspace that cannot be
 * undone. A deletion is asked for every single time, whatever the settings say — that is what
 * [repeatable] turns off, and why the card then offers no "always allow".
 */
data class ToolAsk(
    val service: String,
    val tool: String,
    val arguments: String,
    /** What cannot be taken back, written out for the person to read before they decide. */
    val warning: String? = null,
    /** Whether "always allow" is offered at all. A deletion never offers it. */
    val repeatable: Boolean = true,
)

/**
 * 问了一次之后的结果：明明白白的"行"、明明白白的"不行"，或者**根本没人在**。
 *
 * 后两者必须分开。TA 自己醒过来的时候（主动找人、到点写信），聊天里没有那条流式气泡
 * 可以挂卡片，人也不在看手机 —— 这时什么都没发生。以前这里一并算成"不行"，
 * 于是它在日记和下一次聊天里说"你不同意我改"：一件**从来没发生过**的拒绝，
 * 就这么进了它的记忆。人被拒绝一次还能解释，被人替你拒绝一次是解释不了的。
 */
enum class Consent {
    Yes,
    No,

    /** 问不到人：没人在看（TA 自己在后台醒着），什么都没发生。 */
    Unavailable,
}

class McpException(message: String) : Exception(message)

/** The parts of talking to an MCP service that don't touch the network. */
object Mcp {
    /** The protocol version asked for; the service answers with the one it speaks. */
    const val PROTOCOL = "2025-06-18"

    /** A result longer than this is cut: it goes into the conversation with every message after. */
    const val RESULT_MAX = 6000

    private val json = Json { ignoreUnknownKeys = true }
    private val SECRET_PARAMS = setOf("key", "token", "api_key", "apikey", "secret", "access_token", "password")

    fun request(id: Int, method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        params?.let { put("params", it) }
    }.toString()

    fun notification(method: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }.toString()

    fun initializeParams(version: String): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL)
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") {
            put("name", "Cleos")
            put("version", version)
        }
    }

    /** The result of response [message], or the service's error as an exception. */
    fun resultOf(message: JsonObject): JsonObject {
        (message["error"] as? JsonObject)?.let { e ->
            val text = (e["message"] as? JsonPrimitive)?.contentOrNull ?: e.toString()
            throw McpException("服务回了错误：$text")
        }
        return message["result"] as? JsonObject ?: throw McpException("服务的回应里没有结果")
    }

    /**
     * The response to request [id] in a stream of server-sent events. Events before it
     * (progress, log lines, requests the app can't answer) are passed over.
     */
    fun fromEvents(lines: Iterator<String>, id: Int): JsonObject {
        val data = StringBuilder()
        fun take(): JsonObject? {
            if (data.isEmpty()) return null
            val message = runCatching { json.parseToJsonElement(data.toString()) as? JsonObject }.getOrNull()
            data.setLength(0)
            return message?.takeIf { it.isResponseTo(id) }
        }
        while (lines.hasNext()) {
            val line = lines.next()
            when {
                line.isEmpty() -> take()?.let { return it }
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                }
            }
        }
        return take() ?: throw McpException("服务没给回应就断开了")
    }

    /** 一条 SSE `data:` 里的 JSON（不要求是响应 —— SSE 传输那边自己按 id 对）。 */
    internal fun element(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** A response on its own (the body was JSON), checked to be the one to request [id]. */
    fun fromJson(body: String, id: Int): JsonObject {
        val message = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val response = when (message) {
            is JsonObject -> message
            // A batch: the one that answers.
            is JsonArray -> message.firstOrNull { (it as? JsonObject)?.isResponseTo(id) == true } as? JsonObject
            else -> null
        }
        return response?.takeIf { it.isResponseTo(id) } ?: throw McpException("服务回的不是 MCP 的格式：${body.take(80)}")
    }

    private fun JsonObject.isResponseTo(id: Int): Boolean =
        (this["id"] as? JsonPrimitive)?.contentOrNull == id.toString() && ("result" in this || "error" in this)

    /** The token as sent after "Bearer ": whatever was pasted, without a "Bearer " already in front. */
    fun bearer(token: String): String? =
        token.trim().let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7) else it }.trim().ifEmpty { null }

    /** "Name: value" as a header; null when it isn't one. */
    fun header(line: String): Pair<String, String>? {
        val i = line.indexOf(':')
        if (i <= 0) return null
        val name = line.substring(0, i).trim()
        val value = line.substring(i + 1).trim()
        if (name.isEmpty() || value.isEmpty() || name.any { it.isWhitespace() }) return null
        return name to value
    }

    /** An address fit to show: a key in its query becomes dots. Anywhere a URL is shown, use this. */
    fun displayUrl(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val query = url.substring(q + 1).split('&').joinToString("&") { part ->
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).lowercase() in SECRET_PARAMS) part.substring(0, eq + 1) + "••••" else part
        }
        return url.substring(0, q + 1) + query
    }

    /** What the model calls a tool: mcp_, a bit of the service's id, the tool's name; [A-Za-z0-9_-], at most 64 long. */
    fun fnName(serverId: String, tool: String): String =
        ("mcp_" + serverId.filter { it.isLetterOrDigit() }.take(4) + "_" + tool.replace(Regex("[^A-Za-z0-9_-]"), "_")).take(64)

    /** The tools in a tools/list result. One that can't be read is left out, not the whole service. */
    fun tools(server: McpServer, result: JsonObject): List<McpTool> =
        (result["tools"] as? JsonArray).orEmpty().mapNotNull { runCatching { tool(server, it.jsonObject) }.getOrNull() }

    private fun tool(server: McpServer, o: JsonObject): McpTool {
        val name = o["name"]!!.jsonPrimitive.content.also { require(it.isNotBlank()) }
        val annotations = o["annotations"] as? JsonObject
        return McpTool(
            serverId = server.id,
            serverName = server.name.ifBlank { "外部服务" },
            name = name,
            fnName = fnName(server.id, name),
            title = (o["title"] as? JsonPrimitive ?: annotations?.get("title") as? JsonPrimitive)?.contentOrNull?.ifBlank { null } ?: name,
            description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            inputSchema = schema(o["inputSchema"]),
            readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /** An input schema the chat endpoints accept: an object type, with properties even if none. */
    private fun schema(raw: JsonElement?): JsonObject {
        val o = raw as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(
            o.filterKeys { it != "\$schema" } +
                ("type" to JsonPrimitive("object")) +
                ("properties" to (o["properties"] as? JsonObject ?: JsonObject(emptyMap()))),
        )
    }

    /** A tools/call result as text for the model: its text parts, what the others are, an error marked as one. */
    fun text(result: JsonObject): String {
        val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { part ->
            val o = part as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            when (str("type")) {
                "text" -> str("text")
                "image" -> "[一张图片]"
                "audio" -> "[一段音频]"
                "resource_link" -> "[链接] " + listOfNotNull(str("name"), str("uri")).joinToString(" ")
                "resource" -> ((o["resource"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull ?: "[一份资料]"
                else -> null
            }
        }
        var text = parts.joinToString("\n").ifBlank { result["structuredContent"]?.toString().orEmpty() }.ifBlank { "（没有内容）" }
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) text = "出错了：$text"
        return if (text.length > RESULT_MAX) text.take(RESULT_MAX) + "\n……（太长，后面省略了）" else text
    }

    /** A call's arguments as the person reads them before allowing it: "name：value", one a line. */
    fun preview(arguments: JsonObject): String =
        arguments.entries.take(8).joinToString("\n") { (k, v) ->
            val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k：" + if (value.length > 60) value.take(60) + "…" else value
        }.ifEmpty { "（不带参数）" }
}

/**
 * Talks to MCP services over Streamable HTTP: a JSON-RPC message in a POST, the answer as
 * JSON or as server-sent events. A session begins with initialize; the id the service hands
 * out goes along with every request after, and when the service has forgotten it (404) the
 * client starts over, once.
 */
class McpClient(http: OkHttpClient, private val version: String) {
    // Some tools take a while (ordering goes through the shop's own systems).
    private val http = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val ids = AtomicInteger(1)

    private class Session(val id: String?, val protocol: String, val sse: SseLink? = null)

    private class SessionGone : Exception()

    suspend fun listTools(server: McpServer): List<McpTool> = withSession(server) { s ->
        val tools = ArrayList<McpTool>()
        var cursor: String? = null
        do {
            val result = send(server, s, "tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } })
            tools += Mcp.tools(server, result)
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null && tools.size < MAX_TOOLS)
        tools
    }

    /** Calls [name] with [arguments]; the result as text for the model. */
    suspend fun callTool(server: McpServer, name: String, arguments: JsonObject): String = Mcp.text(callToolRaw(server, name, arguments))

    /** Calls [name] with [arguments]; the result as the service sent it. */
    suspend fun callToolRaw(server: McpServer, name: String, arguments: JsonObject): JsonObject = withSession(server) { s ->
        send(server, s, "tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
    }

    private suspend fun <T> withSession(server: McpServer, block: (Session) -> T): T = withContext(Dispatchers.IO) {
        val key = server.id + "\n" + server.connection
        val session = sessions[key] ?: open(server).also { sessions[key] = it }
        try {
            block(session)
        } catch (e: SessionGone) {
            sessions.remove(key)
            block(open(server).also { sessions[key] = it })
        }
    }

    private fun open(server: McpServer): Session {
        // 两种传输的**第一次握手**不一样：Streamable HTTP 直接 POST，老式 SSE 要先 GET 一条事件流。
        if (server.transport == McpServer.TRANSPORT_SSE) return openSse(server)
        val id = ids.getAndIncrement()
        val (message, sessionId) = post(server, null, Mcp.request(id, "initialize", Mcp.initializeParams(version)), id)
        val result = Mcp.resultOf(message)
        val session = Session(sessionId, (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: Mcp.PROTOCOL)
        // Only an acknowledgment comes back (202); a service that answers otherwise still works.
        runCatching { post(server, session, Mcp.notification("notifications/initialized"), null) }
            .onFailure { Log.w(TAG, "initialized notification failed") }
        return session
    }

    private fun send(server: McpServer, session: Session, method: String, params: JsonObject?): JsonObject {
        val id = ids.getAndIncrement()
        val body = Mcp.request(id, method, params)
        // 老式 SSE：请求 POST 出去，**回应从那条 GET 流里回来**。
        val message = session.sse?.request(body, id) ?: post(server, session, body, id).first
        return Mcp.resultOf(message)
    }

    /** 老式 SSE 的握手：GET 事件流，等它给出要 POST 的端点，再从流里读 initialize 的回应。 */
    private fun openSse(server: McpServer): Session {
        val link = SseLink(server)
        link.start()
        val id = ids.getAndIncrement()
        val message = link.request(Mcp.request(id, "initialize", Mcp.initializeParams(version)), id)
        val result = Mcp.resultOf(message)
        val session = Session(null, (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: Mcp.PROTOCOL, link)
        runCatching { link.post(Mcp.notification("notifications/initialized")) }
            .onFailure { Log.w(TAG, "initialized notification failed") }
        return session
    }

    /**
     * 老式 HTTP+SSE 传输（MCP 2024-11 的写法，很多老服务和中转只有这个）：
     *
     * 1. `GET 地址`（`Accept: text/event-stream`）拿到一条事件流
     * 2. 流里第一个 `endpoint` 事件给出**发请求用的地址**（通常带 session 参数）
     * 3. 之后每个请求 `POST` 到那个地址，**回应从同一条流里回来**（`message` 事件，按 id 对）
     *
     * 后来才有 Streamable HTTP（直接 POST 到地址）—— 两种都实现，是因为不少服务只认旧的。
     */
    private inner class SseLink(private val server: McpServer) {
        private val inbox = LinkedBlockingQueue<JsonObject>()
        private var call: okhttp3.Call? = null
        @Volatile private var endpoint: String? = null
        @Volatile private var closed = false

        fun start() {
            val request = Request.Builder()
                .url(server.url.trim())
                .headers(headers(server, null))
                .header("Accept", "text/event-stream")
                .get()
                .build()
            val c = http.newCall(request)
            call = c
            val response = try {
                c.execute()
            } catch (e: IOException) {
                throw McpException(ioMessage(e))
            }
            if (!response.isSuccessful) {
                val body = runCatching { response.body.string().take(160) }.getOrDefault("")
                throw McpException(describe(response.code, body))
            }
            thread(name = "mcp-sse") {
                try {
                    val reader = response.body.byteStream().bufferedReader(Charsets.UTF_8)
                    var event = ""
                    var data = StringBuilder()
                    fun flush() {
                        val text = data.toString().trim()
                        data = StringBuilder()
                        if (text.isNotEmpty()) {
                            when (event) {
                                "endpoint" -> endpoint = resolveEndpoint(text)
                                "message", "" -> Mcp.element(text)?.let { inbox.put(it) }
                            }
                        }
                        event = ""
                    }
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> flush()
                            line.startsWith("event:") -> { flush(); event = line.substring(6).trim() }
                            line.startsWith("data:") -> data.append(line.substring(5).trim()).append('\n')
                        }
                    }
                    flush()
                } catch (_: Exception) {
                    // 连接断了：等回应的地方会报出来。
                } finally {
                    closed = true
                }
            }
            // 等 endpoint。正常立刻到；等不到就是这条路走不通。
            val deadline = System.currentTimeMillis() + 30_000
            while (endpoint == null && System.currentTimeMillis() < deadline) {
                if (closed) throw McpException("SSE 连接断了，没能拿到要 POST 的地址")
                Thread.sleep(50)
            }
            if (endpoint == null) throw McpException("这个地址 30 秒都没给出要 POST 的端点 —— 可能不是老式 SSE 服务")
        }

        fun post(body: String) {
            val url = endpoint ?: throw McpException("SSE 还没准备好")
            val request = Request.Builder()
                .url(url)
                .headers(headers(server, null))
                .post(body.toRequestBody(JSON))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw McpException(describe(response.code, runCatching { response.body.string().take(160) }.getOrDefault("")))
                    }
                }
            } catch (e: IOException) {
                throw McpException(ioMessage(e))
            }
        }

        /** 发一条请求，等 id 对得上的那条回应；中间的 progress / log 事件跳过。 */
        fun request(body: String, id: Int): JsonObject {
            post(body)
            val deadline = System.currentTimeMillis() + 90_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw McpException(if (closed) "SSE 连接断了，没有等到回应" else "等太久没有回应")
                val message = inbox.poll(left, TimeUnit.MILLISECONDS)
                    ?: throw McpException(if (closed) "SSE 连接断了，没有等到回应" else "等太久没有回应")
                if ((message["id"] as? JsonPrimitive)?.contentOrNull == id.toString()) return message
            }
        }

        private fun resolveEndpoint(text: String): String {
            if (text.startsWith("http://", true) || text.startsWith("https://", true)) return text
            return runCatching { java.net.URI(server.url.trim()).resolve(text).toString() }.getOrDefault(server.url.trim())
        }
    }

    /** 网络错误翻成人话（连接测试和正常调用都用这一份）。 */
    private fun ioMessage(e: IOException): String = when (e) {
        is UnknownHostException -> "找不到这个地址的服务器：检查地址，或者网络是不是断了"
        is SocketTimeoutException -> "等太久没有回应"
        else -> if (e.message?.contains("CLEARTEXT", ignoreCase = true) == true) {
            "只支持 https:// 开头的地址"
        } else {
            "网络出错：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** One POST. [id] null for a notification, which gets no answer. */
    private fun post(server: McpServer, session: Session?, body: String, id: Int?): Pair<JsonObject, String?> {
        val url = server.url.trim()
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            throw McpException("地址要以 https:// 开头")
        }
        val request = Request.Builder()
            .url(url)
            .headers(headers(server, session))
            .post(body.toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404 && session?.id != null) throw SessionGone()
                if (!response.isSuccessful) throw McpException(describe(response.code,
                    McpPayloadStream(response.body.byteStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }))
                val sessionId = response.header("Mcp-Session-Id") ?: session?.id
                if (id == null) return JsonObject(emptyMap()) to sessionId
                val type = response.header("Content-Type").orEmpty()
                val message = McpPayloadStream(response.body.byteStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                    if (type.startsWith("text/event-stream", ignoreCase = true)) {
                        Mcp.fromEvents(generateSequence { reader.readLine() }.iterator(), id)
                    } else {
                        Mcp.fromJson(reader.readText(), id)
                    }
                }
                return message to sessionId
            }
        } catch (e: IOException) {
            throw McpException(
                when (e) {
                    is UnknownHostException -> "找不到这个地址的服务器：检查地址，或者网络是不是断了"
                    is SocketTimeoutException -> "等太久没有回应"
                    else -> if (e.message?.contains("CLEARTEXT", ignoreCase = true) == true) "只支持 https:// 开头的地址" else "网络出错：${e.message ?: e.javaClass.simpleName}"
                },
            )
        }
    }

    private fun headers(server: McpServer, session: Session?): Headers = Headers.Builder().apply {
        add("Accept", "application/json, text/event-stream")
        // 自定义请求头先上（可以多条）；用 add 而不是 set，重名也能按顺序发出去。
        server.effectiveHeaders.forEach { h -> add(h.name, h.value) }
        // **已经自己写了 Authorization 就不再发 Bearer**：一条请求里两个 Authorization
        // 时服务端只认第一个，结果往往是一个莫名其妙的 401。
        if (!server.hasOwnAuthorization) Mcp.bearer(server.token)?.let { add("Authorization", "Bearer $it") }
        session?.id?.let { add("Mcp-Session-Id", it) }
        session?.let { add("MCP-Protocol-Version", it.protocol) }
    }.build()

    private fun describe(code: Int, body: String): String {
        val detail = body.trim().take(120)
        val hint = when (code) {
            401, 403 -> "Token 不对，或者已经失效了（$code）"
            404 -> "地址不对（404）"
            // The old HTTP+SSE transport takes a GET to /sse and a POST elsewhere.
            405 -> "这个地址不收 POST：如果它是老式的 SSE 接口，把传输方式改成「老式 SSE」再试（405）"
            in 500..599 -> "服务那边出错了（$code）"
            else -> "请求失败（$code）"
        }
        return if (detail.isEmpty()) hint else "$hint\n$detail"
    }

    private companion object {
        const val TAG = "McpClient"
        const val MAX_TOOLS = 200
        val JSON = "application/json".toMediaType()
    }
}

/**
 * The tools of the services switched on, kept from one reply to the next: listing them costs
 * a round trip or two, and they seldom change. A service is listed again once its connection
 * changes, when it is tested, and after [FRESH]; one that couldn't be reached is tried again
 * sooner.
 */
class McpHub(
    val servers: McpServers,
    private val client: McpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnostic: (String) -> Unit = {},
) {
    private class Listed(val connection: String, val tools: List<McpTool>, val at: Long, val failed: Boolean)

    private val listed = ConcurrentHashMap<String, Listed>()

    /** The tools of every service switched on. A service that can't be reached brings none. */
    suspend fun tools(): List<McpTool> = coroutineScope {
        val enabled = servers.current().filter { it.enabled && it.url.isNotBlank() }
        enabled.map { async { toolsOf(it) } }.awaitAll().flatten().also {
            diagnostic("event=mcp_discovery enabled=${enabled.size} tools=${it.size}")
        }
    }

    private suspend fun toolsOf(server: McpServer): List<McpTool> {
        val cached = listed[server.id]
        val age = cached?.let { clock() - it.at }
        if (cached != null && cached.connection == server.connection && age!! < if (cached.failed) RETRY else FRESH) {
            listed(server, cached.tools.size, cached.failed, cache = true)
            return cached.tools.withNames(server)
        }
        return try {
            client.listTools(server).also { listed[server.id] = Listed(server.connection, it, clock(), failed = false); listed(server, it.size, false, cache = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Exception prose can include the service URL/token. Keep only structural metadata.
            listed(server, 0, true, cache = false)
            listed[server.id] = Listed(server.connection, emptyList(), clock(), failed = true)
            emptyList()
        }
    }

    private fun listed(server: McpServer, count: Int, failed: Boolean, cache: Boolean) {
        diagnostic("event=${if (failed) "mcp_list_failed" else "mcp_list"} server=${com.cleo.cleos.diagnostics.ToolDiagnosticStore.fingerprint(server.id)} tools=$count failed=$failed cache=$cache")
    }

    /** A service renamed since it was listed shows under its new name. */
    private fun List<McpTool>.withNames(server: McpServer) =
        map { if (it.serverName == server.name.ifBlank { "外部服务" }) it else it.copy(serverName = server.name.ifBlank { "外部服务" }) }

    /** Lists [server]'s tools now, for the settings screen: the tools, or what went wrong. */
    suspend fun test(server: McpServer): Result<List<McpTool>> =
        runCatching { client.listTools(server) }
            .onSuccess { listed[server.id] = Listed(server.connection, it, clock(), failed = false); listed(server, it.size, false, cache = false) }
            .onFailure { if (it is CancellationException) throw it; listed(server, 0, true, cache = false) }

    suspend fun call(server: McpServer, tool: McpTool, arguments: JsonObject): String = Mcp.text(callRaw(server, tool, arguments))

    suspend fun callRaw(server: McpServer, tool: McpTool, arguments: JsonObject): JsonObject {
        val name = com.cleo.cleos.diagnostics.ToolDiagnosticStore.label(tool.fnName)
        val missing = ToolSchemaCompatibility.missingDependencies(tool.inputSchema, arguments)
        if (missing.isNotEmpty()) diagnostic("event=mcp_call_failed reason=missing_dependency names=$name")
        require(missing.isEmpty()) { "工具参数缺少依赖字段：${missing.joinToString("、")}。本次没有执行。" }
        diagnostic("event=mcp_call names=$name")
        return try {
            client.callToolRaw(server, tool.name, arguments).also {
                diagnostic("event=${if ((it["isError"] as? JsonPrimitive)?.booleanOrNull == true) "mcp_call_failed" else "mcp_call_done"} names=$name")
            }
        } catch (e: CancellationException) {
            diagnostic("event=cancelled names=$name")
            throw e
        } catch (e: Exception) {
            diagnostic("event=mcp_call_failed names=$name")
            throw e
        }
    }

    private companion object {
        const val TAG = "McpHub"
        const val FRESH = 30 * 60_000L
        const val RETRY = 2 * 60_000L
    }
}
