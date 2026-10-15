package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/** What came of copying a picked file in: the copy's name, and what it is called. */
data class StoredFile(val file: String, val name: String, val size: Long, val mimeType: String?)

/** A file that cannot be read: too big, empty, or not there. The message is shown to the person. */
class FileException(message: String) : Exception(message)

/**
 * Files copied into the app's own storage, beside ImageStore and for the same reason: a
 * picker's URI grant can lapse and the file can be moved, and a message should not lose its
 * attachment because of either.
 *
 * The text read out of a file is kept next to it as a `.txt` of the same stem, so that a long
 * conversation does not parse the same PDF again on every turn.
 */
class FileStore(context: Context) {
    private val resolver = context.contentResolver
    val dir: File = File(context.filesDir, "files").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    /**
     * Copies a picked file in and reads its text out, in one step: what the chat needs to show
     * the attachment, and what the TA needs to read it (FileText). A file that cannot be read
     * is still copied in and returned with [MessageFile.error] set, so the chat can say which
     * file failed and why rather than the pick doing nothing.
     *
     * A kind the app cannot open at all is refused outright: there is nothing to show.
     */
    suspend fun add(uri: Uri, maxBytes: Long = MAX_BYTES): MessageFile {
        val stored = import(uri, maxBytes)
        val kind = fileKind(stored.name, stored.mimeType)
        if (kind == null) {
            delete(listOf(stored.file))
            throw FileException("「${stored.name}」这种文件读不了。现在支持 TXT、MD、PDF、DOCX。")
        }
        val base = MessageFile(stored.file, stored.name, stored.size, kind)
        val bytes = withContext(Dispatchers.IO) { runCatching { file(stored.file).readBytes() }.getOrNull() }
            ?: return base.copy(error = "文件读不出来了")
        val read = runCatching { FileText.read(bytes, kind) }
            .getOrElse { e ->
                // Too big, encrypted, or damaged: the file stays, with the reason on it.
                return base.copy(error = e.message ?: "文件损坏或格式不支持")
            }
        if (read.text.isBlank()) {
            return base.copy(error = "里头没有能读的文字")
        }
        writeText(stored.file, read.text)
        return base.copy(chars = read.text.length, pages = read.pages)
    }

    /**
     * Copies [uri] in, up to [maxBytes]. The name from the picker is kept for the person to
     * read, but the copy is filed under a fresh id: two files can share a name, and neither
     * the name nor the folder it came from is something the app should trust as a path.
     */
    suspend fun import(uri: Uri, maxBytes: Long): StoredFile = withContext(Dispatchers.IO) {
        val meta = query(uri)
        val name = meta.first?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "文件"
        val mime = resolver.getType(uri)
        val extension = name.substringAfterLast('.', "").take(8).lowercase().replace(Regex("[^a-z0-9]"), "")
        val file = File(dir, UUID.randomUUID().toString() + if (extension.isEmpty()) "" else ".$extension")
        val tmp = File(dir, file.name + ".tmp")
        try {
            val stream = resolver.openInputStream(uri) ?: throw FileException("打不开这个文件")
            val size = stream.use { copy(it, tmp, maxBytes) }
            if (size == 0L) throw FileException("这个文件是空的")
            if (!tmp.renameTo(file)) throw FileException("文件没能保存下来")
            StoredFile(file.name, name, if (meta.second > 0) meta.second else size, mime)
        } finally {
            tmp.delete()
        }
    }

    /** [input] into [out], refusing to go past [maxBytes]: a picked file is not to fill the phone. */
    private fun copy(input: InputStream, out: File, maxBytes: Long): Long {
        var total = 0L
        out.outputStream().use { sink ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw FileException("文件太大了（超过 ${maxBytes / 1024 / 1024} MB），请换一个小一些的")
                sink.write(buffer, 0, read)
            }
        }
        return total
    }

    /** The name and size the picker knows, which a plain content:// URI does not carry. */
    private fun query(uri: Uri): Pair<String?, Long> = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0 && !c.isNull(nameIndex)) c.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !c.isNull(sizeIndex)) c.getLong(sizeIndex) else 0L
            name to size
        }
    }.getOrNull() ?: (null to 0L)

    /** Writes the text read out of a file, where later turns will find it again. */
    suspend fun writeText(name: String, text: String) = withContext(Dispatchers.IO) {
        runCatching { file(textName(name)).writeText(text, Charsets.UTF_8) }
        Unit
    }

    /** The text read out of [name], or null when it was never written or has been cleared. */
    suspend fun readText(name: String): String? = read(name, 0, Int.MAX_VALUE)

    /** The first [limit] characters of that text, for what a reply writes into a message. */
    suspend fun read(name: String, limit: Int): String? = read(name, 0, limit)

    /**
     * [limit] characters of that text from [offset] — one read_file call. Only that slice is
     * pulled into memory: a long document is read a few paragraphs at a time, which is the
     * whole point of keeping its text on disk rather than in the conversation.
     */
    suspend fun read(name: String, offset: Int, limit: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(textName(name))
            if (!f.isFile) return@runCatching null
            f.inputStream().use { stream ->
                val reader = stream.reader(Charsets.UTF_8)
                var skipped = 0
                while (skipped < offset) {
                    val n = reader.skip((offset - skipped).toLong())
                    if (n <= 0) break
                    skipped += n.toInt()
                }
                val buffer = CharArray(limit)
                var read = 0
                while (read < limit) {
                    val n = reader.read(buffer, read, limit - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read)
            }
        }.getOrNull()
    }

    /** Whether the text is still there: a file whose words have been cleared can't be read. */
    suspend fun hasText(name: String): Boolean = withContext(Dispatchers.IO) { file(textName(name)).isFile }

    fun delete(names: Collection<String>) {
        names.forEach { name ->
            runCatching { file(name).delete() }
            runCatching { file(textName(name)).delete() }
        }
    }

    companion object {
        /**
         * Where the text read out of [name] lives. It carries the whole name plus `.text`
         * rather than replacing the extension: a plain .txt or .md file would otherwise be
         * filed under the name it already has, and the words written back would land on top of
         * the copy the person sent — which is what happened while this replaced the extension.
         */
        fun textName(name: String): String = "$name.text"

        /**
         * The largest file the chat will take. A PDF of a whole book would be read into memory
         * whole, and this is a phone: past this the person is asked for a smaller file instead.
         */
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}
