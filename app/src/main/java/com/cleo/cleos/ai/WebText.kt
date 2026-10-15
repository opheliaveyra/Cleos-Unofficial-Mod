package com.cleo.cleos.ai

import java.nio.charset.Charset

/**
 * A web page turned into words. Everything here works on strings, so it can be tried against
 * real pages without a phone: HTML in, reading order out.
 *
 * This is not a browser and does not try to be. It drops what is not the page's writing —
 * scripts, styles, the head, navigation, forms — turns block elements into line breaks, and
 * keeps what is left. A page built entirely out of JavaScript comes back nearly empty, which
 * is honest: there was nothing to read without running it.
 */
object WebText {
    /** What the page says it is: whoever declares it, header first, then the meta tag. */
    fun charsetOf(contentType: String, bytes: ByteArray): Charset {
        val header = Regex("charset=[\"']?([\\w.:-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        // The meta tag is looked for in the first few kilobytes, in ASCII, so that it reads the
        // same whether the file turns out to be UTF-8 or GBK.
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val meta = Regex("charset=[\"']?([\\w.:-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return runCatching { Charset.forName((header ?: meta ?: "utf-8").trim()) }.getOrDefault(Charsets.UTF_8)
    }

    /** The text between the title tags, tidied; null when there is none. */
    fun titleOf(html: String): String? =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?.let { tidy(unescape(it)) }
            ?.takeIf { it.isNotEmpty() }

    /** The words on the page, in the order they are read. */
    fun of(html: String): String {
        var body = html
        // What is not the page's writing: scripts, styles, the head, and anything a browser
        // would keep but a reader never sees.
        body = body.replace(Regex("(?is)<(script|style|noscript|svg|head|iframe|template|form|select|button)[^>]*>.*?</\\1\\s*>"), " ")
        body = body.replace(Regex("(?s)<!--.*?-->"), " ")
        body = body.replace(Regex("(?is)<(script|style|noscript|svg|iframe|template)[^>]*/?>"), " ")
        // The article itself when the page says where it is; the whole body otherwise.
        val article = Regex("(?is)<article[^>]*>(.*?)</article>").find(body)?.groupValues?.get(1)
            ?: Regex("(?is)<main[^>]*>(.*?)</main>").find(body)?.groupValues?.get(1)
        val scope = article ?: body
        val withBreaks = scope
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)<hr\\s*/?>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "\n- ")
            .replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article|blockquote|pre|dd|dt|td|th|figcaption)>"), "\n")
        val stripped = withBreaks.replace(Regex("(?s)<[^>]*>"), "")
        return tidy(unescape(stripped))
    }

    /** Entities turned back into the characters they stand for. */
    fun unescape(text: String): String {
        if ('&' !in text) return text
        return Regex("&(#x?[0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});").replace(text) { match ->
            val body = match.groupValues[1]
            val code = when {
                body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)
                body.startsWith("#") -> body.drop(1).toIntOrNull()
                else -> null
            }
            if (code != null) {
                // An out-of-range number is left as it was rather than turned into a replacement char.
                return@replace if (code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
            }
            NAMED[body] ?: NAMED[body.lowercase()] ?: match.value
        }
    }

    /** Runs of spaces and blank lines brought down to one, the way a page reads. */
    fun tidy(text: String): String = text
        .replace('\u00A0', ' ')
        .replace('\u3000', ' ')
        .replace(Regex("[ \\t\\u000B\\f\\r]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·", "bull" to "•",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "copy" to "©", "reg" to "®", "trade" to "™", "times" to "×", "divide" to "÷",
        "deg" to "°", "euro" to "€", "pound" to "£", "yen" to "¥", "sect" to "§", "para" to "¶",
    )
}
