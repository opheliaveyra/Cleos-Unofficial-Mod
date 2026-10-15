package com.cleo.cleos.data

import android.content.Context
import java.io.File

/** One thing in the workspace: a file or a folder. [path] is how the TA names it back. */
data class WorkspaceEntry(val name: String, val path: String, val directory: Boolean, val size: Long, val modifiedAt: Long)

/** Something the workspace will not do: a path that leaves it, or a size it may not reach. */
class WorkspaceException(message: String) : Exception(message)

/**
 * The TA's own folder: somewhere to keep what it writes — notes, drafts, a list it is building,
 * a little script — and to come back to on a later day. It is not the chat and not the person's
 * files: a place of its own, with a life of its own.
 *
 * Every path is relative to one directory under the app's private storage and is checked before
 * it is used. `..` and absolute paths are refused outright rather than normalised, and the
 * resolved file is checked to be inside the folder a second time: a model that writes
 * `../../databases/cleos.db` is either confused or repeating something it read inside a file.
 */
class Workspace(val dir: File) {
    /**
     * 一个 TA 自己的那一份。**工作区跟着角色走**：切到另一个 TA 就是另一个文件夹，
     * 一个 TA 写的笔记，别的 TA 既读不到、也覆盖不到 —— 提示词里跟它说的是
     * 「你自己的地方」，那就得真的是它自己的。
     */
    constructor(context: Context, companionId: Long) : this(File(root(context), companionId.toString()))

    init {
        dir.mkdirs()
    }

    /** The folder [path] names, or throws when it is not one the TA may use. */
    private fun resolve(path: String): File =
        resolveIn(dir, path) ?: throw WorkspaceException("只能在自己的工作区里读写：路径里不能有 .. ，也不能从根目录开始")

    /** What is directly inside [path] (the root when it is blank), folders first. */
    fun list(path: String = ""): List<WorkspaceEntry> {
        val at = resolve(path)
        if (!at.exists()) throw WorkspaceException("没有「${path.ifBlank { "/" }}」这个地方")
        if (!at.isDirectory) throw WorkspaceException("「$path」是一个文件，不是文件夹")
        return at.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { file ->
                val relative = file.relativeTo(dir).path.replace(File.separatorChar, '/')
                WorkspaceEntry(file.name, relative, file.isDirectory, if (file.isDirectory) 0 else file.length(), file.lastModified())
            }
    }

    /** Whether anything is there at all: the tools are only offered once the TA has written something. */
    fun isEmpty(): Boolean = dir.listFiles().orEmpty().isEmpty()

    /** Whether something sits at [path]; a path the TA may not use counts as nothing. */
    fun exists(path: String): Boolean = runCatching { resolve(path).exists() }.getOrDefault(false)

    /** Which of the two it is, and how big, for the card that asks whether it may be deleted. */
    fun describe(path: String): String {
        val at = resolve(path)
        if (!at.exists()) throw WorkspaceException("工作区里没有「$path」")
        if (!at.isDirectory) return "文件，${at.length()} 字节"
        val files = at.walkTopDown().filter { it.isFile }.toList()
        return "文件夹，里面 ${files.size} 个文件、共 ${files.sumOf { it.length() }} 字节"
    }

    /** [limit] characters of the file [path], from [offset]. */
    fun read(path: String, offset: Int, limit: Int): String {
        val at = resolve(path)
        if (!at.exists()) throw WorkspaceException("工作区里没有「$path」")
        if (at.isDirectory) throw WorkspaceException("「$path」是一个文件夹，读不了")
        if (at.length() > MAX_FILE) throw WorkspaceException("这个文件太大了，超过 ${MAX_FILE / 1024} KB")
        return at.inputStream().use { stream ->
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
    }

    /** How long the file at [path] is, in characters; 0 when it is not there yet. */
    fun length(path: String): Int {
        val at = runCatching { resolve(path) }.getOrNull() ?: return 0
        if (!at.isFile) return 0
        return runCatching { at.readText(Charsets.UTF_8).length }.getOrDefault(0)
    }

    /** Writes [text] to [path], making the folders above it. An existing file is replaced. */
    fun write(path: String, text: String) {
        val at = resolve(path)
        if (at.isDirectory) throw WorkspaceException("「$path」已经是一个文件夹了")
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE) throw WorkspaceException("一次最多写 ${MAX_FILE / 1024} KB，这次是 ${bytes.size / 1024} KB")
        at.parentFile?.mkdirs()
        check(room(bytes.size - (if (at.isFile) at.length() else 0L))) { "工作区放不下了" }
        at.writeBytes(bytes)
    }

    /** [text] at the end of [path], the way a person keeps adding to a note. */
    fun append(path: String, text: String) {
        val at = resolve(path)
        if (at.isDirectory) throw WorkspaceException("「$path」是一个文件夹")
        val bytes = text.toByteArray(Charsets.UTF_8)
        val after = (if (at.isFile) at.length() else 0L) + bytes.size
        if (after > MAX_FILE) throw WorkspaceException("这个文件会超过 ${MAX_FILE / 1024} KB，换个文件写或者拆开")
        check(room(bytes.size.toLong())) { "工作区放不下了" }
        at.parentFile?.mkdirs()
        at.appendBytes(bytes)
    }

    /** A folder at [path]; already having one is not an error. */
    fun makeDir(path: String) {
        val at = resolve(path)
        if (at.isFile) throw WorkspaceException("「$path」已经是一个文件了")
        if (!at.mkdirs() && !at.isDirectory) throw WorkspaceException("这个文件夹建不了")
    }

    /** [from] to [to], which is how a file gets renamed. */
    fun move(from: String, to: String) {
        val source = resolve(from)
        val target = resolve(to)
        if (!source.exists()) throw WorkspaceException("工作区里没有「$from」")
        if (target.exists()) throw WorkspaceException("「$to」已经存在了，换个名字或者先删掉它")
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) throw WorkspaceException("没能移动，可能跨了文件夹或有同名文件")
    }

    /** Everything under [path], gone. The caller must have asked the person first. */
    fun delete(path: String): Int {
        val at = resolve(path)
        if (at == dir) throw WorkspaceException("不能把整个工作区删掉")
        if (!at.exists()) throw WorkspaceException("工作区里没有「$path」")
        val files = if (at.isDirectory) at.walkTopDown().count() else 1
        if (!at.deleteRecursively()) throw WorkspaceException("有文件没能删掉")
        return files
    }

    /** How many files and how many bytes are in here, for the line in settings. */
    fun usage(): Pair<Int, Long> {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return files.size to files.sumOf { it.length() }
    }

    /** Whether [adding] more bytes still fits. */
    private fun room(adding: Long): Boolean = adding <= 0 || usage().second + adding <= MAX_TOTAL

    companion object {
        /** 所有 TA 的工作区都在这底下，一个 TA 一个子文件夹。 */
        fun root(context: Context): File = File(context.filesDir, "workspace")

        /** One file may not be bigger than this: the workspace is for writing, not for storing. */
        const val MAX_FILE = 1 shl 20

        /** The whole workspace may not be bigger than this. */
        const val MAX_TOTAL = 20L shl 20

        /** The most files it may hold. */
        const val MAX_FILES = 500

        /**
         * Where [path] lands inside [root], or null when it would leave it. `..`, `.` and a
         * leading slash are refused outright rather than normalised — a model that writes
         * `../../databases/cleos.db` is either confused or repeating something it read inside a
         * file — and the answer is checked against [root] a second time, in case the file system
         * resolved a link somewhere else.
         *
         * A plain function over two files, so the rule itself can be tested without a phone.
         */
        fun resolveIn(root: File, path: String): File? {
            val asked = path.trim()
            // A leading slash is refused rather than quietly treated as relative: a model that
            // writes /etc/passwd believes it is writing outside, and it should be told no.
            if (asked.startsWith("/")) return null
            val clean = asked.trim('/')
            if (clean.isEmpty()) return root
            if (clean.split('/').any { it == ".." || it == "." }) return null
            val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
            val target = File(root, clean)
            val real = runCatching { target.canonicalFile }.getOrNull() ?: return null
            if (real != base && !real.path.startsWith(base.path + File.separator)) return null
            return target
        }
    }
}
