package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** One result: what it is called, where it is, and a line or two of what it says. */
data class SearchHit(val title: String, val url: String, val snippet: String)

/**
 * The search services the app knows how to talk to. Each one's key is filed by its endpoint
 * (SecretStore), the same way a chat or voice key is, so switching between them keeps both.
 *
 * [keyUrl] is where a key is applied for: that link is shown on the settings page, because
 * "go and get a key" is not something a person should have to search for.
 */
enum class SearchService(val label: String, val keyUrl: String, val hint: String, val endpoint: String) {
    Bocha(
        "博查",
        "https://open.bochaai.com/",
        "国内直连最快，中文搜索好；微信扫码就能注册，有免费额度。",
        "https://api.bochaai.com/v1/web-search",
    ),
    Tavily(
        "Tavily",
        "https://app.tavily.com/home",
        "专给 AI 用的搜索，每月 1000 次免费；英文内容更顺手。",
        "https://api.tavily.com/search",
    ),
    Serper(
        "Serper",
        "https://serper.dev/",
        "Google 的搜索结果，注册送 2500 次。",
        "https://google.serper.dev/search",
    ),
    Zhipu(
        "智谱",
        "https://open.bigmodel.cn/",
        "已经有智谱账号的话最省事，不用再注册一家。",
        "https://open.bigmodel.cn/api/paas/v4/web_search",
    ),
    ;

    companion object {
        fun of(name: String?): SearchService? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Searching the web through whichever service the person set up.
 *
 * The four services differ in the shape of their request — the field names, and where the key
 * goes — so each gets its own small sender. Their *answers* differ too (webPages.value,
 * results, organic, search_result), and rather than write a parser per service that goes stale
 * the moment one of them tidies its JSON, the answer is walked for the first list of objects
 * that each carry a title and a link. What a search result is does not vary that much.
 */
class WebSearch(http: OkHttpClient, private val secrets: SecretStore) : WebSearcher {
    private val http = http.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(settings: AppSettings, query: String, count: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        val chosen = SearchService.of(settings.searchService)
            ?: throw WebException("还没选搜索服务：去 设置 › 联网搜索 里选一家、把 Key 填上。")
        val key = secrets.key(chosen.endpoint)?.takeIf { it.isNotBlank() }
            ?: throw WebException("${chosen.label} 的 Key 还没填：去 设置 › 联网搜索 里填上。")
        val request = Request.Builder()
            .url(chosen.endpoint)
            .header("Content-Type", "application/json")
            .apply {
                if (chosen == SearchService.Serper) header("X-API-KEY", key.trim())
                else header("Authorization", "Bearer ${key.trim()}")
            }
            .post(body(chosen, query, count).toRequestBody(JSON))
            .build()
        try {
            http.fetch(request) { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw WebException(why(response.code, text))
                hitsOf(json.parseToJsonElement(text), count)
            }
        } catch (e: IOException) {
            throw WebException(
                when (e) {
                    is UnknownHostException -> "找不到 ${chosen.label} 的服务器：检查一下网络"
                    is SocketTimeoutException -> "等太久没有回应，${chosen.label} 那边可能很慢"
                    else -> "网络出错：${e.message ?: e.javaClass.simpleName}"
                },
            )
        }
    }

    /** What each service wants to be asked: the field names are their own. */
    private fun body(service: SearchService, query: String, count: Int): String = buildJsonObject {
        when (service) {
            SearchService.Bocha -> {
                put("query", query)
                put("count", count)
                put("summary", true)
            }
            SearchService.Tavily -> {
                put("query", query)
                put("max_results", count)
            }
            SearchService.Serper -> {
                put("q", query)
                put("num", count)
            }
            SearchService.Zhipu -> {
                put("search_engine", "search_std")
                put("search_query", query)
            }
        }
    }.toString()

    /** A failure said in words the person can act on. */
    private fun why(code: Int, body: String): String = when (code) {
        401, 403 -> "Key 不对，或者已经失效了（HTTP $code）"
        402 -> "账户余额不足（HTTP 402）"
        429 -> "请求太频繁，或者免费额度用完了（HTTP 429）"
        else -> "服务回了 HTTP $code：${body.take(120)}"
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The results out of whatever shape the answer came in. Depth-limited and size-limited:
         * this walks a document the app did not write, and it must come back either way.
         */
        fun hitsOf(root: JsonElement, count: Int): List<SearchHit> {
            val found = ArrayList<SearchHit>()
            fun walk(node: JsonElement, depth: Int) {
                if (found.size >= count || depth > 8) return
                when (node) {
                    is JsonArray -> {
                        val looked = node.mapNotNull { hitOf(it) }
                        if (looked.isNotEmpty()) {
                            found.addAll(looked.take(count - found.size))
                            return
                        }
                        node.forEach { walk(it, depth + 1) }
                    }
                    is JsonObject -> node.values.forEach { walk(it, depth + 1) }
                    else -> Unit
                }
            }
            walk(root, 0)
            return found.distinctBy { it.url }.take(count)
        }

        private fun hitOf(node: JsonElement): SearchHit? {
            val o = node as? JsonObject ?: return null
            val url = text(o, "url") ?: text(o, "link") ?: return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            val title = text(o, "title") ?: text(o, "name") ?: return null
            if (title.isBlank()) return null
            val snippet = listOf("content", "snippet", "summary", "description", "text")
                .firstNotNullOfOrNull { text(o, it) }
                .orEmpty()
            return SearchHit(title.trim(), url.trim(), snippet.trim().take(SNIPPET_MAX))
        }

        private fun text(o: JsonObject, key: String): String? =
            (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        /** A snippet is a line to decide on, not the page itself: read_webpage goes and gets that. */
        const val SNIPPET_MAX = 500
    }
}

/** Searching, as the tool box sees it: one call, whichever service is set up behind it. */
interface WebSearcher {
    suspend fun search(settings: AppSettings, query: String, count: Int): List<SearchHit>
}
