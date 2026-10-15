package com.cleo.cleos.data

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Reads the words out of a PDF locally: no service, no library, the same way DocxText reads
 * a Word file.
 *
 * It walks the file's objects, follows the page tree, decodes each page's content stream and
 * maps the codes back to characters through the font's ToUnicode table — the table a PDF
 * carries so that its text can be selected and copied. A PDF made of scans has no such table
 * and comes back empty, with that said plainly; one with a password is refused rather than
 * half-read. Anything the parser does not recognise is skipped rather than guessed at, since
 * a wrong guess produces words the person never wrote.
 */
internal object PdfText {
    private const val MAX_OBJECTS = 40_000
    private const val MAX_STREAM = 32 * 1024 * 1024
    /** How many pages are walked; a 2000-page scan is not something to read on a phone. */
    private const val MAX_PAGES = 500

    fun read(bytes: ByteArray): ExtractedText {
        if (bytes.size < 8) throw FileException("这个 PDF 是空的")
        if (!looksLikePdf(bytes)) throw FileException("这不是一个能读的 PDF 文件")
        val objects = scanObjects(bytes)
        if (objects.isEmpty()) throw FileException("这个 PDF 读不了：文件似乎已损坏")
        val trailer = trailerDict(bytes, objects)
        if (trailer?.map?.containsKey("Encrypt") == true) throw FileException("这个 PDF 有密码，先去掉密码再发过来")
        val pages = pageDicts(objects, trailer)
        if (pages.isEmpty()) throw FileException("这个 PDF 里找不到页面")
        val out = StringBuilder()
        var read = 0
        for (page in pages.take(MAX_PAGES)) {
            val text = pageText(page, objects)
            if (text.isNotBlank()) {
                out.append(text.trim()).append("\n\n")
                read++
            }
            if (out.length > FileText.MAX_CHARS) break
        }
        val final = out.toString().trim()
        if (final.isEmpty()) throw FileException("这个 PDF 里没有文字：可能是扫描件或整页图片，读不出内容")
        return ExtractedText(final, pages.size)
    }

    private fun looksLikePdf(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size - 5, 1024)
        for (i in 0..limit) {
            if (bytes[i] == '%'.code.toByte() && bytes[i + 1] == 'P'.code.toByte() && bytes[i + 2] == 'D'.code.toByte() &&
                bytes[i + 3] == 'F'.code.toByte() && bytes[i + 4] == '-'.code.toByte()
            ) return true
        }
        return false
    }

    // ---- the objects of the file ------------------------------------------------------------

    /** Every value a PDF can hold. Only the parts this reader needs are modelled. */
    private sealed interface Value

    private object PEnd : Value

    private object PNull : Value

    private class PNum(val value: Double) : Value

    private class PKeyword(val name: String) : Value

    private class PName(val name: String) : Value

    private class PString(val bytes: ByteArray) : Value

    private class PArray(val items: List<Value>) : Value

    private class PDict(val map: Map<String, Value>) : Value

    private class PRef(val number: Int) : Value

    private class PStream(val dict: PDict, val raw: ByteArray) : Value

    private fun PDict.name(key: String): String? = (map[key] as? PName)?.name

    private fun PDict.word(key: String): String? = when (val v = map[key]) {
        is PName -> v.name
        is PKeyword -> v.name
        is PString -> String(v.bytes, Charsets.ISO_8859_1).trim()
        else -> null
    }

    private fun PDict.number(key: String): Double? = (map[key] as? PNum)?.value

    private fun isWhitespace(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00
    }

    private fun isDelimiter(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code ||
            c == '/'.code || c == '%'.code || c == 0x20 || c == 0x0A || c == 0x0D ||
            c == 0x09 || c == 0x0C || c == 0x00
    }

    private fun matches(data: ByteArray, at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > data.size) return false
        for (i in text.indices) if (data[at + i] != text[i].code.toByte()) return false
        if (at + text.length < data.size && !isDelimiter(data[at + text.length])) return false
        return true
    }

    private fun indexOf(data: ByteArray, text: String, from: Int): Int {
        val first = text[0].code.toByte()
        var i = maxOf(from, 0)
        while (i <= data.size - text.length) {
            if (data[i] == first && matches(data, i, text)) return i
            i++
        }
        return -1
    }

    /**
     * A reader over a run of bytes. It never throws and never runs backwards: a file with a
     * broken object loses that object, not the paragraphs around it.
     */
    private class Lexer(private val data: ByteArray, var pos: Int = 0) {
        fun skipSpace() {
            while (pos < data.size) {
                val c = data[pos].toInt() and 0xFF
                if (c == '%'.code) {
                    while (pos < data.size && data[pos] != '\n'.code.toByte() && data[pos] != '\r'.code.toByte()) pos++
                } else if (c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00) {
                    pos++
                } else {
                    return
                }
            }
        }

        /** Past the next [word] that stands on its own, for what must not be parsed as PDF. */
        fun skipTo(word: String) {
            val at = indexOf(data, word, pos)
            pos = if (at < 0) data.size else at + word.length
        }

        fun parseValue(): Value? {
            skipSpace()
            while (pos < data.size) {
                val c = data[pos].toInt() and 0xFF
                when {
                    c == '/'.code -> { pos++; return PName(readName()) }
                    c == '('.code -> { pos++; return PString(readLiteralString()) }
                    c == '<'.code -> {
                        if (pos + 1 < data.size && data[pos + 1] == '<'.code.toByte()) { pos += 2; return parseDict() }
                        pos++
                        return PString(readHexString())
                    }
                    c == '['.code -> { pos++; return parseArray() }
                    c == ']'.code || c == '>'.code || c == '}'.code -> { pos++; return PEnd }
                    c == ')'.code || c == '{'.code -> { pos++; continue }
                    (c >= '0'.code && c <= '9'.code) || c == '-'.code || c == '+'.code || c == '.'.code -> return parseNumberOrRef()
                    !isDelimiter(data[pos]) -> return keywordOrNull()
                    else -> pos++
                }
            }
            return null
        }

        private fun readName(): String {
            val start = pos
            while (pos < data.size && !isDelimiter(data[pos])) pos++
            // #20 and the like: a name's escape for a byte that is not printable.
            val raw = String(data, start, pos - start, Charsets.ISO_8859_1)
            if (!raw.contains('#')) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                if (raw[i] == '#' && i + 2 < raw.length) {
                    val hex = raw.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) { out.append(hex.toChar()); i += 3; continue }
                }
                out.append(raw[i]); i++
            }
            return out.toString()
        }

        private fun readLiteralString(): ByteArray {
            val out = ByteArrayOutputStream()
            var depth = 1
            while (pos < data.size) {
                val b = data[pos]
                when (b.toInt() and 0xFF) {
                    '\\'.code -> {
                        pos++
                        if (pos >= data.size) break
                        when (val e = data[pos].toInt() and 0xFF) {
                            'n'.code -> out.write('\n'.code)
                            'r'.code -> out.write('\r'.code)
                            't'.code -> out.write('\t'.code)
                            'b'.code -> out.write(0x08)
                            'f'.code -> out.write(0x0C)
                            '('.code, ')'.code, '\\'.code -> out.write(e)
                            '\n'.code -> { /* a backslash before a newline continues the string */ }
                            '\r'.code -> if (pos + 1 < data.size && data[pos + 1] == '\n'.code.toByte()) pos++
                            in '0'.code..'7'.code -> {
                                var octal = e - '0'.code
                                var digits = 1
                                while (digits < 3 && pos + 1 < data.size && (data[pos + 1].toInt() and 0xFF) in '0'.code..'7'.code) {
                                    pos++
                                    octal = octal * 8 + ((data[pos].toInt() and 0xFF) - '0'.code)
                                    digits++
                                }
                                out.write(octal and 0xFF)
                            }
                            else -> out.write(e)
                        }
                        pos++
                    }
                    '('.code -> { depth++; out.write(b.toInt()); pos++ }
                    ')'.code -> { depth--; pos++; if (depth == 0) break }
                    else -> { out.write(b.toInt()); pos++ }
                }
            }
            return out.toByteArray()
        }

        private fun readHexString(): ByteArray {
            val out = ByteArrayOutputStream()
            var high = -1
            while (pos < data.size) {
                val c = data[pos].toInt() and 0xFF
                pos++
                if (c == '>'.code) break
                val digit = when {
                    c >= '0'.code && c <= '9'.code -> c - '0'.code
                    c >= 'a'.code && c <= 'f'.code -> c - 'a'.code + 10
                    c >= 'A'.code && c <= 'F'.code -> c - 'A'.code + 10
                    else -> -1
                }
                if (digit < 0) continue
                if (high < 0) high = digit else { out.write(high * 16 + digit); high = -1 }
            }
            if (high >= 0) out.write(high * 16)
            return out.toByteArray()
        }

        private fun parseArray(): Value {
            val items = ArrayList<Value>()
            while (pos < data.size) {
                val before = pos
                val value = parseValue() ?: break
                if (value === PEnd) break
                items.add(value)
                if (pos == before) pos++
                if (items.size > 4096) break
            }
            return PArray(items)
        }

        private fun parseDict(): Value {
            val map = HashMap<String, Value>()
            while (pos < data.size) {
                skipSpace()
                if (pos >= data.size) break
                val c = data[pos].toInt() and 0xFF
                if (c == '>'.code) {
                    pos++
                    if (pos < data.size && data[pos] == '>'.code.toByte()) pos++
                    break
                }
                if (c != '/'.code) {
                    // Not a key: a broken dictionary still yields the pairs that are whole.
                    val before = pos
                    if (parseValue() === PEnd) break
                    if (pos == before) pos++
                    continue
                }
                pos++
                val key = readName()
                val value = parseValue() ?: break
                if (value === PEnd) break
                map[key] = value
            }
            return PDict(map)
        }

        private fun readNumber(): Double? {
            val start = pos
            if (pos < data.size && (data[pos] == '+'.code.toByte() || data[pos] == '-'.code.toByte())) pos++
            var digits = 0
            while (pos < data.size && (data[pos].toInt() and 0xFF) in '0'.code..'9'.code) { pos++; digits++ }
            if (pos < data.size && data[pos] == '.'.code.toByte()) {
                pos++
                while (pos < data.size && (data[pos].toInt() and 0xFF) in '0'.code..'9'.code) { pos++; digits++ }
            }
            if (digits == 0) { pos = start; return null }
            return String(data, start, pos - start, Charsets.US_ASCII).toDoubleOrNull()
        }

        private fun parseNumberOrRef(): Value {
            val start = pos
            val first = readNumber() ?: run { pos = start + 1; return PNull }
            val afterFirst = pos
            skipSpace()
            if (pos < data.size && (data[pos].toInt() and 0xFF) in '0'.code..'9'.code) {
                val second = readNumber()
                skipSpace()
                if (second != null && second >= 0 && matches(data, pos, "R")) {
                    val number = first.toInt()
                    if (number >= 0 && first == number.toDouble()) { pos += 1; return PRef(number) }
                }
            }
            pos = afterFirst
            return PNum(first)
        }

        private fun keywordOrNull(): Value {
            val start = pos
            while (pos < data.size && !isDelimiter(data[pos])) pos++
            if (pos == start) { pos++; return PNull }
            return when (val word = String(data, start, pos - start, Charsets.ISO_8859_1)) {
                "true", "false", "null" -> PKeyword(word)
                else -> PKeyword(word)
            }
        }
    }

    /** Every `N G obj … endobj` in the file, by object number, plus what is packed inside streams. */
    private fun scanObjects(data: ByteArray): Map<Int, Value> {
        val out = HashMap<Int, Value>()
        var i = 0
        while (i + 3 < data.size && out.size < MAX_OBJECTS) {
            if (data[i] == 'o'.code.toByte() && data[i + 1] == 'b'.code.toByte() && data[i + 2] == 'j'.code.toByte() &&
                (i + 3 >= data.size || isDelimiter(data[i + 3]))
            ) {
                val number = objectNumberBefore(data, i)
                if (number != null) {
                    val lexer = Lexer(data, i + 3)
                    val value = lexer.parseValue()
                    if (value != null && value !== PEnd) {
                        val (full, next) = readStream(data, lexer.pos, value)
                        out[number] = full
                        i = maxOf(next, i + 1)
                        continue
                    }
                }
            }
            i++
        }
        expandObjectStreams(out)
        return out
    }

    /**
     * Objects packed inside object streams (`/Type /ObjStm`, PDF 1.5 and later). A file written
     * by Word or by Chrome's print dialog keeps its catalog and page tree in there rather than
     * as top-level `N G obj` blocks, so a reader that only walks the surface of the file finds
     * no pages at all — and those are exactly the files people send.
     *
     * A stream holds a header of `objectNumber offsetInBytes` pairs, then the objects
     * themselves, the first one starting at `/First`.
     */
    private fun expandObjectStreams(objects: HashMap<Int, Value>) {
        val packed = objects.values.filterIsInstance<PStream>().filter { it.dict.name("Type") == "ObjStm" }
        for (stream in packed) {
            val count = (stream.dict.map["N"] as? PNum)?.value?.toInt() ?: continue
            val first = (stream.dict.map["First"] as? PNum)?.value?.toInt() ?: continue
            if (count <= 0 || first < 0) continue
            val data = decodeStream(stream, objects) ?: continue
            if (first >= data.size) continue
            val pairs = ArrayList<Pair<Int, Int>>(count)
            val header = Lexer(data, 0)
            var read = 0
            while (read < count) {
                val number = (header.parseValue() as? PNum)?.value?.toInt() ?: break
                val offset = (header.parseValue() as? PNum)?.value?.toInt() ?: break
                pairs.add(number to offset)
                read++
            }
            for ((number, offset) in pairs) {
                // A top-level object with the same number was written later and wins.
                if (number in objects) continue
                val at = first + offset
                if (at < 0 || at >= data.size) continue
                val value = Lexer(data, at).parseValue() ?: continue
                if (value === PEnd) continue
                objects[number] = value
            }
        }
    }

    /** The `N` of `N G obj`, read backwards from the `obj`. */
    private fun objectNumberBefore(data: ByteArray, objAt: Int): Int? {
        var p = objAt - 1
        while (p >= 0 && isWhitespace(data[p])) p--
        val genEnd = p
        while (p >= 0 && (data[p].toInt() and 0xFF) in '0'.code..'9'.code) p--
        if (p == genEnd) return null
        while (p >= 0 && isWhitespace(data[p])) p--
        val numEnd = p
        while (p >= 0 && (data[p].toInt() and 0xFF) in '0'.code..'9'.code) p--
        if (p == numEnd) return null
        return String(data, p + 1, numEnd - p, Charsets.US_ASCII).toIntOrNull()
    }

    /**
     * A dictionary directly followed by `stream` becomes a stream. Its length comes from
     * /Length when that is a plain number, and from the `endstream` keyword otherwise — the
     * length may itself be an indirect object, which is not resolved here.
     */
    private fun readStream(data: ByteArray, from: Int, value: Value): Pair<Value, Int> {
        if (value !is PDict) return value to from
        var p = from
        while (p < data.size && isWhitespace(data[p])) p++
        if (!matches(data, p, "stream")) return value to from
        p += 6
        if (p < data.size && data[p] == '\r'.code.toByte()) p++
        if (p < data.size && data[p] == '\n'.code.toByte()) p++
        val start = minOf(p, data.size)
        var end = -1
        val declared = (value.map["Length"] as? PNum)?.value?.toInt()
        if (declared != null && declared >= 0 && start + declared <= data.size) {
            var q = start + declared
            while (q < data.size && isWhitespace(data[q])) q++
            if (matches(data, q, "endstream")) end = start + declared
        }
        if (end < 0) {
            val found = indexOf(data, "endstream", start)
            var q = if (found < 0) data.size else found
            if (q > start && data[q - 1] == '\n'.code.toByte()) q--
            if (q > start && data[q - 1] == '\r'.code.toByte()) q--
            end = q
        }
        val after = indexOf(data, "endstream", end).let { if (it < 0) data.size else it + 9 }
        return PStream(value, data.copyOfRange(start, end.coerceIn(start, data.size))) to after
    }

    private fun deref(value: Value?, objects: Map<Int, Value>): Value? = when (value) {
        is PRef -> objects[value.number]
        else -> value
    }

    private fun trailerDict(data: ByteArray, objects: Map<Int, Value>): PDict? {
        // A classic file ends with a trailer dictionary.
        var at = data.size
        repeat(4) {
            val found = lastIndexOf(data, "trailer", at)
            if (found >= 0) {
                val parsed = Lexer(data, found + 7).parseValue()
                if (parsed is PDict) return parsed
                at = found
            }
        }
        // A cross-reference stream holds the same entries instead (PDF 1.5 and later).
        for (value in objects.values) {
            val dict = when (value) {
                is PStream -> value.dict
                is PDict -> value
                else -> null
            }
            if (dict?.name("Type") == "XRef") return dict
        }
        return null
    }

    private fun lastIndexOf(data: ByteArray, text: String, before: Int): Int {
        var i = minOf(before, data.size) - text.length
        while (i >= 0) {
            if (matches(data, i, text)) return i
            i--
        }
        return -1
    }

    // ---- pages ------------------------------------------------------------------------------

    private fun pageDicts(objects: Map<Int, Value>, trailer: PDict?): List<PDict> {
        val out = ArrayList<PDict>()
        val catalog = (deref(trailer?.map?.get("Root"), objects) as? PDict)
            ?: objects.values.firstOrNull { it is PDict && it.name("Type") == "Catalog" } as? PDict
        val root = catalog?.let { deref(it.map["Pages"], objects) as? PDict }
        if (root != null) walkPages(root, objects, out, emptyMap(), 0)
        if (out.isNotEmpty()) return out
        // No usable page tree: every object that says it is a page, in the order it was written.
        objects.entries.sortedBy { it.key }.forEach { (_, value) ->
            (value as? PDict)?.takeIf { it.name("Type") == "Page" }?.let { out.add(it) }
        }
        return out
    }

    private fun walkPages(node: PDict, objects: Map<Int, Value>, out: MutableList<PDict>, inherited: Map<String, Value>, depth: Int) {
        if (depth > 64 || out.size >= MAX_PAGES) return
        val merged = HashMap<String, Value>(inherited)
        for (key in listOf("Resources", "MediaBox", "CropBox", "Rotate")) node.map[key]?.let { merged[key] = it }
        // A page's own entries win over what it inherits.
        val page = PDict(merged + node.map)
        if (node.name("Type") == "Page") {
            out.add(page)
            return
        }
        val kids = deref(node.map["Kids"], objects) as? PArray ?: return
        for (kid in kids.items) (deref(kid, objects) as? PDict)?.let { walkPages(it, objects, out, merged, depth + 1) }
    }

    // ---- streams ----------------------------------------------------------------------------

    private fun filters(dict: PDict): List<String> = when (val filter = dict.map["Filter"]) {
        is PName -> listOf(filter.name)
        is PArray -> filter.items.mapNotNull { (it as? PName)?.name }
        else -> emptyList()
    }

    private fun decodeStream(stream: PStream, objects: Map<Int, Value>): ByteArray? {
        var data = stream.raw
        for (filter in filters(stream.dict)) {
            data = when (filter) {
                "FlateDecode", "Fl" -> inflate(data) ?: return null
                "ASCIIHexDecode", "AHx" -> asciiHex(data)
                "ASCII85Decode", "A85" -> ascii85(data)
                "RunLengthDecode", "RL" -> runLength(data)
                else -> return null
            }
        }
        return data
    }

    private fun inflate(data: ByteArray): ByteArray? {
        var start = 0
        while (start < data.size && isWhitespace(data[start])) start++
        zlib(data, start, false)?.let { return it }
        return zlib(data, start, true)
    }

    private fun zlib(data: ByteArray, start: Int, raw: Boolean): ByteArray? {
        if (start >= data.size) return null
        val inflater = Inflater(raw)
        inflater.setInput(data, start, data.size - start)
        val out = ByteArrayOutputStream(maxOf(1024, data.size * 3))
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val n = runCatching { inflater.inflate(buffer) }.getOrElse { break }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, n)
                if (out.size() > MAX_STREAM) break
            }
        } finally {
            inflater.end()
        }
        // A stream whose tail is missing still yields what came through; that is more useful
        // than nothing, and the alternative is losing a whole page over a few bytes.
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun asciiHex(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var high = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == '>'.code) break
            val digit = when {
                c in '0'.code..'9'.code -> c - '0'.code
                c in 'a'.code..'f'.code -> c - 'a'.code + 10
                c in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> -1
            }
            if (digit < 0) continue
            if (high < 0) high = digit else { out.write(high * 16 + digit); high = -1 }
        }
        if (high >= 0) out.write(high * 16)
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var tuple = 0L
        var count = 0
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (isWhitespace(b)) continue
            if (c == '~'.code) break
            if (c == 'z'.code && count == 0) {
                repeat(4) { out.write(0) }
                continue
            }
            if (c < '!'.code || c > 'u'.code) continue
            tuple = tuple * 85 + (c - '!'.code)
            if (++count == 5) {
                out.write(((tuple shr 24) and 0xFF).toInt())
                out.write(((tuple shr 16) and 0xFF).toInt())
                out.write(((tuple shr 8) and 0xFF).toInt())
                out.write((tuple and 0xFF).toInt())
                tuple = 0
                count = 0
            }
        }
        if (count > 0) {
            for (i in count until 5) tuple = tuple * 85 + 84
            val bytes = ByteArray(4)
            for (i in 0..3) bytes[i] = ((tuple shr (24 - 8 * i)) and 0xFF).toByte()
            for (i in 0 until count - 1) out.write(bytes[i].toInt())
        }
        return out.toByteArray()
    }

    private fun runLength(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val length = data[i].toInt() and 0xFF
            i++
            when {
                length == 128 -> break
                length < 128 -> {
                    val end = minOf(i + length + 1, data.size)
                    out.write(data, i, end - i)
                    i = end
                }
                else -> {
                    if (i >= data.size) break
                    val b = data[i].toInt() and 0xFF
                    i++
                    repeat(257 - length) { out.write(b) }
                }
            }
            if (out.size() > MAX_STREAM) break
        }
        return out.toByteArray()
    }

    // ---- fonts ------------------------------------------------------------------------------

    /** How one font's byte codes read as characters. */
    private class Font(private val twoByte: Boolean, private val map: Map<Int, String>) {
        fun decode(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size)
            if (twoByte) {
                var i = 0
                while (i + 1 < bytes.size) {
                    val code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                    map[code]?.let { out.append(it) }
                    i += 2
                }
            } else {
                for (b in bytes) {
                    val code = b.toInt() and 0xFF
                    val mapped = map[code]
                    if (mapped != null) out.append(mapped) else if (code >= 0x20) out.append((code and 0xFF).toChar())
                }
            }
            return out.toString()
        }

        companion object {
            val PLAIN = Font(false, emptyMap())
        }
    }

    private fun pageFonts(page: PDict, objects: Map<Int, Value>): Map<String, Font> {
        val resources = deref(page.map["Resources"], objects) as? PDict ?: return emptyMap()
        val fonts = deref(resources.map["Font"], objects) as? PDict ?: return emptyMap()
        val out = HashMap<String, Font>()
        for ((name, value) in fonts.map) {
            val dict = deref(value, objects) as? PDict ?: continue
            out[name] = readFont(dict, objects)
        }
        return out
    }

    private fun readFont(dict: PDict, objects: Map<Int, Value>): Font {
        // A composite font's codes are two bytes wide; a simple font's are one.
        val twoByte = dict.name("Subtype") == "Type0"
        val toUnicode = deref(dict.map["ToUnicode"], objects) as? PStream
        val cmap = toUnicode?.let { stream ->
            decodeStream(stream, objects)?.let { parseCMap(String(it, Charsets.ISO_8859_1)) }
        } ?: emptyMap()
        return Font(twoByte, cmap)
    }

    /** The pairs in a ToUnicode CMap: what a byte code in the stream stands for. */
    private fun parseCMap(text: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        // Hex digits inside <> may be spaced out; the pairs are what matter.
        val tidy = Regex("<([0-9A-Fa-f\\s]*)>").replace(text) { "<" + it.groupValues[1].filter { c -> !c.isWhitespace() } + ">" }
        val charBlock = Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL)
        for (block in charBlock.findAll(tidy)) {
            for (pair in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(block.groupValues[1])) {
                val code = pair.groupValues[1].toIntOrNull(16) ?: continue
                out[code] = hexToText(pair.groupValues[2])
            }
        }
        val rangeBlock = Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL)
        for (block in rangeBlock.findAll(tidy)) {
            val body = block.groupValues[1]
            for (range in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(body)) {
                fillRange(out, range.groupValues[1], range.groupValues[2], range.groupValues[3])
            }
            for (range in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*\\[([^\\]]*)]").findAll(body)) {
                val low = range.groupValues[1].toIntOrNull(16) ?: continue
                val high = range.groupValues[2].toIntOrNull(16) ?: continue
                Regex("<([0-9A-Fa-f]+)>").findAll(range.groupValues[3]).forEachIndexed { index, item ->
                    if (low + index > high) return@forEachIndexed
                    out[low + index] = hexToText(item.groupValues[1])
                }
            }
        }
        return out
    }

    private fun fillRange(out: HashMap<Int, String>, lowHex: String, highHex: String, dstHex: String) {
        val low = lowHex.toIntOrNull(16) ?: return
        val high = highHex.toIntOrNull(16) ?: return
        if (high < low || high - low > 65_535) return
        val base = hexToText(dstHex)
        for (i in 0..(high - low)) {
            // A range walks the destination's last code unit; longer destinations (a ligature)
            // repeat, which is what they mean.
            out[low + i] = if (base.length == 1) (base[0].code + i).toChar().toString() else base
        }
    }

    private fun hexToText(hex: String): String {
        if (hex.length < 2) return ""
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            if (high < 0 || low < 0) return ""
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return String(bytes, Charsets.UTF_16BE)
    }

    // ---- a page's content stream --------------------------------------------------------------

    private fun pageText(page: PDict, objects: Map<Int, Value>): String {
        val fonts = pageFonts(page, objects)
        val out = StringBuilder()
        val contents = page.map["Contents"]
        val streams = when (val value = deref(contents, objects)) {
            is PStream -> listOf(value)
            is PArray -> value.items.mapNotNull { deref(it, objects) as? PStream }
            else -> emptyList()
        }
        for (stream in streams) {
            val bytes = decodeStream(stream, objects) ?: continue
            out.append(Content(fonts).run(bytes))
        }
        return out.toString()
    }

    /**
     * The text-showing part of a content stream: which font is in use, where the line sits,
     * and what each `Tj`, `TJ`, `'` and `"` puts on the page.
     */
    private class Content(private val fonts: Map<String, Font>) {
        private val out = StringBuilder()
        private val operands = ArrayList<Value>()
        private var font: Font? = null
        /** The baseline the last text was drawn on; NaN until there has been one. */
        private var lineY = Double.NaN
        /** Where the text about to be shown sits: moved by Td/TD, set outright by Tm. */
        private var textY = 0.0
        private var leading = 0.0
        private var lastWasSpace = true
        /** The font size in force, for telling a word gap from ordinary kerning. */
        private var fontSize = 12.0
        /** How far the last run was moved sideways, and where Tm last put it. */
        private var pendingGap = 0.0
        private var lastX = 0.0

        fun run(data: ByteArray): String {
            val lexer = Lexer(data)
            while (true) {
                val token = lexer.parseValue() ?: break
                if (token is PKeyword) {
                    if (token.name == "BI") skipInlineImage(lexer) else operate(token.name)
                    operands.clear()
                } else {
                    operands.add(token)
                    if (operands.size > 64) operands.removeAt(0)
                }
            }
            if (out.isNotEmpty()) out.append('\n')
            return out.toString()
        }

        /** `BI … ID <bytes> EI`: the bytes are not text and must not be parsed as any. */
        private fun skipInlineImage(lexer: Lexer) {
            lexer.skipTo("EI")
        }

        private fun operate(name: String) {
            when (name) {
                // BT 重置文本矩阵，但不重置「上一次画在哪一行」：一页上的文字常常分成好几个
                // BT/ET 块（Word 导出的页眉就是这样，"第"、"1"、"页"各是一个块），
                // 行位置是跨块的 —— 每块都算新行的话，一行会散成好几行。
                "BT" -> { textY = 0.0; lastX = 0.0; pendingGap = 0.0 }
                // ET 只是结束文本对象：换不换行由位置说了算，不由它说了算 ——
                // 同一行上的两个块之间（Word 的页眉就是这样）不该断开。
                "ET" -> Unit
                "Tf" -> {
                    font = (operands.getOrNull(0) as? PName)?.let { fonts[it.name] }
                    fontSize = number(0) ?: fontSize
                }
                "TL" -> number(0)?.let { leading = it }
                "Tc", "Tw", "Tz", "Ts" -> {}
                "Td", "TD" -> {
                    val dx = number(0) ?: 0.0
                    val dy = number(1) ?: 0.0
                    if (name == "TD") leading = -dy
                    // 同一行里横向挪得远，说明这儿是个词间距；往下走才是换行。
                    pendingGap = if (abs(dy) <= 2.0) abs(dx) else 0.0
                    textY += dy
                    moveLine()
                }
                "Tm" -> {
                    val e = number(4) ?: 0.0
                    val f = number(5) ?: 0.0
                    pendingGap = if (lineY.isNaN() || abs(f - lineY) <= 2.0) abs(e - lastX) else 0.0
                    lastX = e
                    textY = f
                    moveLine()
                }
                "T*" -> { textY -= leading; moveLine() }
                "Tj" -> show(operands.getOrNull(0))
                "'" -> { textY -= leading; moveLine(); show(operands.getOrNull(0)) }
                "\"" -> { textY -= leading; moveLine(); show(operands.getOrNull(2)) }
                "TJ" -> show(operands.getOrNull(0))
            }
        }

        private fun number(index: Int): Double? = (operands.getOrNull(index) as? PNum)?.value

        /**
         * A step down the page is a new line; the same baseline is the same line. A page that
         * puts every glyph at its own position — which is what a Chinese exam paper exported
         * from Word does — would otherwise come out one character per line.
         */
        private fun moveLine() {
            if (lineY.isNaN() || abs(textY - lineY) > 2.0) breakLine()
            lineY = textY
        }

        private fun breakLine() {
            // A blank line is worth keeping; three of them are not.
            if (out.isEmpty()) return
            if (out.endsWith("\n\n")) return
            if (!out.endsWith("\n")) out.append('\n')
            lastWasSpace = true
        }

        private fun show(value: Value?) {
            when (value) {
                is PString -> emit(font?.decode(value.bytes) ?: Font.PLAIN.decode(value.bytes))
                is PArray -> for (item in value.items) {
                    when (item) {
                        is PString -> emit(font?.decode(item.bytes) ?: Font.PLAIN.decode(item.bytes))
                        // A big negative adjustment is where a word space sits in a TJ array.
                        is PNum -> if (item.value < -120) emit(" ")
                        else -> {}
                    }
                }
                else -> {}
            }
        }

        private fun emit(text: String) {
            if (text.isEmpty()) return
            // A space already at the end of what was written is the same space.
            val word = if (lastWasSpace) text.trimStart() else text
            if (word.isEmpty()) return
            // A run placed further along the line is a new word — unless both sides are Chinese,
            // where every character has its own position and no space belongs between them.
            if (!lastWasSpace && pendingGap > fontSize * 0.22 && out.isNotEmpty() && !bothCjk(out.last(), word.first())) {
                out.append(' ')
            }
            out.append(word)
            lastWasSpace = word.last() == ' '
            pendingGap = 0.0
        }

        /** 中文和全角标点：它们之间不加空格。 */
        private fun bothCjk(before: Char, after: Char): Boolean = isCjk(before) && isCjk(after)

        private fun isCjk(c: Char): Boolean = c.code.let {
            it in 0x3000..0x303F || it in 0x3400..0x4DBF || it in 0x4E00..0x9FFF ||
                it in 0xF900..0xFAFF || it in 0xFF00..0xFFEF
        }
    }
}
