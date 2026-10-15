package com.cleo.cleos.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** A page as the model gets to see it: what it is called, and the words on it. */
data class WebPage(val url: String, val title: String, val text: String)

/** A page that would not come back, with a reason the person can read. */
class WebException(message: String) : Exception(message)

/** Fetches a web page and turns it into text, for read_webpage. */
interface WebReader {
    suspend fun read(url: String): WebPage
}

/**
 * Reads a page over the network and strips it down to what is written on it.
 *
 * Two things are deliberately narrow here. Only http and https are fetched, and an address
 * that resolves to this phone or to the local network is refused before the request goes out:
 * the URL comes from a model, which may be repeating something it read inside a page or a file,
 * and `http://192.168.1.1/` or `http://169.254.169.254/` are exactly what such a line would ask
 * for. And the answer is capped: a page is read into memory whole, so a fifty-megabyte video
 * behind an innocent-looking link must not be.
 */
class WebPages(http: OkHttpClient) : WebReader {
    private val http = http.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun read(url: String): WebPage = withContext(Dispatchers.IO) {
        val target = WebAddress.checked(url) ?: throw WebException("这个地址打不开：只能是 http 或 https 的公开网址")
        val request = Request.Builder()
            .url(target)
            .header("User-Agent", AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        try {
            http.fetch(request) { response ->
                if (!response.isSuccessful) throw WebException("这个网页打不开（HTTP ${response.code}）")
                val kind = response.header("Content-Type").orEmpty().lowercase()
                val body = response.body
                val length = body.contentLength()
                if (length > MAX_BYTES) throw WebException("这个网页太大了（${length / 1024 / 1024} MB），换个地址吧")
                val bytes = body.bytes()
                if (bytes.size > MAX_BYTES) throw WebException("这个网页太大了，换个地址吧")
                val charset = WebText.charsetOf(kind, bytes)
                val raw = String(bytes, charset)
                val text = if (kind.contains("html") || raw.trimStart().startsWith("<")) WebText.of(raw) else raw.trim()
                WebPage(response.request.url.toString(), WebText.titleOf(raw).orEmpty(), text)
            }
        } catch (e: IOException) {
            throw WebException(
                when (e) {
                    is UnknownHostException -> "找不到这个网站：地址是不是写错了，或者网络不通"
                    is SocketTimeoutException -> "等太久没有回应，这个网站可能很慢或者打不开"
                    else -> "网络出错：${e.message ?: e.javaClass.simpleName}"
                },
            )
        }
    }

    private companion object {
        /** A page is read into memory whole; past this it is not worth it. */
        const val MAX_BYTES = 6L * 1024 * 1024

        const val AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"
    }
}

/**
 * Whether an address is one the app is willing to fetch. Kept apart from the fetching, and
 * made of plain functions, because this is the part that has to be right: everything else
 * here is HTML handling.
 */
object WebAddress {
    /** [url] as an http(s) address, or null when it is anything else — including the phone itself. */
    fun checked(url: String): String? {
        val asked = url.trim()
        if (asked.isEmpty()) return null
        val lowered = asked.lowercase()
        if (!lowered.startsWith("http://") && !lowered.startsWith("https://")) return null
        val host = hostOf(asked) ?: return null
        if (isLocal(host)) return null
        return asked
    }

    /** The host part of [url], lowercased and without brackets or a port. */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        var host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        // user@host: the credentials are not the host.
        host = host.substringAfterLast('@')
        if (host.startsWith("[")) host = host.substringBefore(']').trimStart('[')
        else host = host.substringBeforeLast(':', host)
        return host.trim().lowercase().takeIf { it.isNotEmpty() }
    }

    /**
     * Names and numbers that mean this phone or the network around it. A model told to "look up
     * the address in the file" can be walked straight into a router's page or a cloud metadata
     * endpoint; those are not pages to read, they are the inside of the network.
     */
    fun isLocal(host: String): Boolean {
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return true
        val literal = host.removeSurrounding("[", "]")
        if (literal.contains(':')) return true // IPv6: not fetched, which also covers ::1
        val parts = literal.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val first = parts[0].toInt()
            val second = parts[1].toInt()
            return when {
                first == 0 || first == 10 || first == 127 -> true
                first == 172 && second in 16..31 -> true
                first == 192 && second == 168 -> true
                first == 169 && second == 254 -> true
                first >= 224 -> true
                else -> false
            }
        }
        // A name that resolves to one of those is the same thing wearing a hat.
        return runCatching { InetAddress.getAllByName(host).any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress } }
            .getOrDefault(false)
    }
}
