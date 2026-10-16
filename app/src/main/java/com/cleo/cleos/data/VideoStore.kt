package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 一条视频存进来之后的结果。 */
sealed interface StoredVideo {
    /** 存好了，[name] 是它在库里的名字。 */
    data class Ok(val name: String, val bytes: Long) : StoredVideo

    /** 太大了发不出去 —— [why] 是一句给人看的话。 */
    data class TooBig(val bytes: Long, val why: String) : StoredVideo

    /** 读不了（不是视频、没有权限、系统不给）。 */
    data class Unreadable(val why: String) : StoredVideo
}

/**
 * 视频存进 App 自己的目录，然后**原样**发给能看视频的模型。
 *
 * **为什么不压一压再发**：视频压缩要么很慢、要么要拖一整套编解码库进来。
 * 这个 App 里没有 FFmpeg，也不该为了发一条视频就塞一个进去。
 * 所以做法是**照原样存、照原样发，超了就说超了** —— 不许偷偷抽帧糊弄过去。
 *
 * **上限**是小米 MiMo 定的（mimo.mi.com 的视频理解文档）：base64 之后的字符串不超过 50 MB。
 * base64 会胀大约 1/3，所以原文件卡在 [MAX_BYTES]（35 MB）。
 * 别家（SambaNova）限 100 MB，取小的那个，两边都能发。
 */
class VideoStore(context: Context) {
    private val resolver = context.contentResolver
    val dir: File = File(context.filesDir, "videos").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    /**
     * 把 [uri] 拷进来。
     *
     * 拷贝前先看大小：**早说不比晚说好** —— 拷了 200 MB 进来说"发不出去"，
     * 白占人家的存储。
     */
    suspend fun import(uri: Uri): StoredVideo = withContext(Dispatchers.IO) {
        val size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (size > MAX_BYTES) return@withContext StoredVideo.TooBig(size, tooBig(size))
        val name = UUID.randomUUID().toString() + suffix(uri)
        val tmp = File(dir, "$name.tmp")
        try {
            val copied = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                } ?: return@withContext StoredVideo.Unreadable("这个文件打不开")
            }.getOrElse { return@withContext StoredVideo.Unreadable("这个文件读不了") }
            // 有些来源报不出大小（-1），拷完才知道真多大 —— 那就拷完再判一次。
            if (copied > MAX_BYTES) {
                tmp.delete()
                return@withContext StoredVideo.TooBig(copied, tooBig(copied))
            }
            check(tmp.renameTo(File(dir, name))) { "视频未能保存" }
            StoredVideo.Ok(name, copied)
        } catch (e: Exception) {
            tmp.delete()
            StoredVideo.Unreadable(e.message ?: "这个视频读不了")
        }
    }

    /** 发得出去吗（base64 之后不超上限）。 */
    fun sendable(name: String): Boolean = file(name).let { it.isFile && it.length() in 1..MAX_BYTES }

    /**
     * 转成 `data:video/mp4;base64,...` —— 小米 MiMo 的 Base64 传法。
     * 太大或者读不出来返回 null（调用方要说话，不能默默发个空的）。
     */
    suspend fun dataUrl(name: String): String? = withContext(Dispatchers.IO) {
        val f = file(name)
        if (!f.isFile || f.length() > MAX_BYTES) return@withContext null
        runCatching {
            val bytes = f.readBytes()
            "data:" + mimeOf(name) + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    fun delete(names: Collection<String>) {
        names.forEach { runCatching { File(dir, it).delete() } }
    }

    private fun tooBig(bytes: Long): String =
        "这条视频 ${mb(bytes)}，太大了发不出去（上限 ${mb(MAX_BYTES)}）。" +
            "剪短一点，或者换一段小的 —— 我不会偷偷抽几帧糊弄过去。"

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1024.0 / 1024.0)

    /** 从 uri 的 mime 里取个后缀；认不出来就当 mp4（最常见）。 */
    private fun suffix(uri: Uri): String =
        when (resolver.getType(uri)?.lowercase()) {
            "video/quicktime" -> ".mov"
            "video/x-msvideo", "video/avi" -> ".avi"
            "video/x-ms-wmv" -> ".wmv"
            else -> ".mp4"
        }

    /** 存的名字里带着后缀，发的时候按它写 mime。 */
    private fun mimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            else -> "video/mp4"
        }

    companion object {
        /**
         * 原文件的上限。
         *
         * base64 会胀约 1/3，小米那边卡的是"编码后的字符串 ≤ 50 MB"，
         * 所以 35 MB 的文件编码出来约 47 MB，留一点余量。
         * 手机随手拍的十几秒视频一般在这个范围内。
         */
        const val MAX_BYTES = 35L * 1024 * 1024
    }
}
