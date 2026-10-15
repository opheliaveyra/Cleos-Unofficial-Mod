package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A file sent with a message: the copy the app keeps, and what was read out of it.
 *
 * [file] is a name inside FileStore; the text taken out of it is kept beside it under the
 * name FileStore.textName gives, so that the model reads the words once rather than the app
 * parsing the file again on every turn. [chars] is how long that text is; a file whose
 * [error] is set was read and could not be turned into text, and the TA is told so rather
 * than being left to guess.
 */
@Serializable
data class MessageFile(
    /** The copy in FileStore: the bytes as they came, whatever the picker's URI does later. */
    val file: String,
    /** What the person called it: "报告.pdf". Shown in the chat and told to the TA. */
    val name: String,
    /** Its size in bytes, as the picker reported it. */
    val size: Long,
    /** One of [KINDS]: txt, md, pdf, docx. */
    val kind: String,
    /** How many characters the extracted text has; 0 while it is still being read, or on failure. */
    val chars: Int = 0,
    /** Pages, for a PDF; null for the others. */
    val pages: Int? = null,
    /** Why it could not be read, when it could not. */
    val error: String? = null,
) {
    /** Whether the text is there to be read. */
    val ready: Boolean get() = error == null && chars > 0

    companion object {
        const val TXT = "txt"
        const val MD = "md"
        const val PDF = "pdf"
        const val DOCX = "docx"
        val KINDS = setOf(TXT, MD, PDF, DOCX)
    }
}

object MessageFiles {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(files: List<MessageFile>): String? = if (files.isEmpty()) null else json.encodeToString(files)

    fun decode(raw: String?): List<MessageFile> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString<List<MessageFile>>(raw) }.getOrDefault(emptyList())
}

/** "1.2 MB", "812 KB", "97 B": what a size reads as in the chat. */
fun readableSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** The extension a file name carries, lowercased, or "" when it has none. */
fun fileExtension(name: String): String = name.substringAfterLast('.', "").lowercase()

/**
 * What kind of file [name] is, judged by its name and then by the type the picker gave it:
 * null when it is not one of the four the chat reads.
 */
fun fileKind(name: String, mimeType: String?): String? {
    when (fileExtension(name)) {
        "txt", "text", "log" -> return MessageFile.TXT
        "md", "markdown" -> return MessageFile.MD
        "pdf" -> return MessageFile.PDF
        "docx" -> return MessageFile.DOCX
    }
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        mime == "application/pdf" -> MessageFile.PDF
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> MessageFile.DOCX
        mime == "text/markdown" -> MessageFile.MD
        mime.startsWith("text/") -> MessageFile.TXT
        else -> null
    }
}
