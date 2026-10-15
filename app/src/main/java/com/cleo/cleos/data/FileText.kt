package com.cleo.cleos.data

import java.util.zip.Inflater
import java.util.zip.DataFormatException

/** What was read out of a file. [pages] is the page count for a PDF, null for the others. */
data class ExtractedText(val text: String, val pages: Int? = null)

/**
 * Reads the words out of a TXT, MD, DOCX or PDF, locally: no service is asked and no file
 * leaves the phone, the same way DocxText reads a Word file.
 *
 * Everything is capped: a file is read into memory only up to its store's limit, and the
 * text taken out of it stops at [MAX_CHARS]. What the model is sent is a slice of that
 * anyway (ChatFiles), so the cap is there to keep a mis-picked huge file from filling the
 * disk, not to decide what the TA may read.
 */
internal object FileText {
    /** As much text as one file may contribute, in characters. */
    const val MAX_CHARS = 1_000_000

    fun read(bytes: ByteArray, kind: String): ExtractedText = when (kind) {
        MessageFile.TXT, MessageFile.MD -> ExtractedText(normalize(decodeText(bytes)).take(MAX_CHARS))
        MessageFile.DOCX -> ExtractedText(normalize(DocxText.read(bytes)).take(MAX_CHARS))
        MessageFile.PDF -> PdfText.read(bytes)
            .let { ExtractedText(normalize(it.text).take(MAX_CHARS), it.pages) }
        else -> throw FileException("这种文件读不了")
    }

    /**
     * Plain text as a person's file would hold it: the byte order mark first, then UTF-8,
     * then GBK, which is what a Chinese Windows machine writes a .txt in. A file that is
     * neither is read as Latin-1 rather than refused, so a slightly odd encoding costs a few
     * wrong characters instead of the whole file.
     */
    internal fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        strictUtf8(bytes)?.let { return it }
        return runCatching { String(bytes, charset("GBK")) }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    /** One shape for every kind: no carriage returns, no NULs, no runs of blank lines. */
    private fun normalize(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\u0000", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
