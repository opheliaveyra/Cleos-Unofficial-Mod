package com.cleo.cleos.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * 从视频里抽几帧当图片用。
 *
 * 为什么是"抽帧"而不是"把视频发给模型"：
 *
 * · **几乎没有模型直接吃视频** —— 你手上那些（DeepSeek、Claude、小米）都只看图；
 *   真能吃视频的是 Gemini、Qwen-VL 那一类，而且各家传法都不一样。
 * · **视频太大** —— 一条几分钟的视频几十上百 MB，塞进请求里不现实。
 *
 * 抽帧是**本地做的**，不需要任何额外服务，抽出来的帧走的是现成的图片管线
 * （看图模型 → 转述成文字 → 交给聊天的模型）。
 *
 * 代价说清楚：**它看到的是"几张静图"，动作和过程会漏** ——
 * 你说"他先转身再挥手"，模型可能只看到"站着"和"挥手"两帧。
 */
object VideoFrames {
    /** 一条视频抽几帧。五帧够看清"这是什么、有谁、大概在干什么"了。 */
    const val DEFAULT_COUNT = 5

    /**
     * 抽帧。抽不出来（不是视频、没有时长、系统不给解）就返回空表，由调用方决定怎么办。
     *
     * 取点用 **5% ~ 95%** 而不是 0 ~ 100%：**首帧经常是黑的**（有些相机和应用会先给一帧全黑），
     * **末帧也常常取不到**（`OPTION_CLOSEST_SYNC` 只能给到最后一个关键帧之前）。
     */
    fun frames(context: Context, uri: Uri, count: Int = DEFAULT_COUNT): List<Bitmap> {
        if (count <= 0) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.trim()?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return emptyList()
            (0 until count).mapNotNull { i ->
                // count 是 1 的时候除法会炸，单帧就取正中间。
                val fraction = if (count == 1) 0.5 else 0.05 + 0.9 * i / (count - 1)
                runCatching {
                    retriever.getFrameAtTime((durationMs * fraction * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 这条视频有多长（毫秒）；读不出来就是 0。用来在聊天里显示"0:42"这种。 */
    fun durationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.trim()?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 毫秒变成 `1:23` 这种，给人看；超过一小时是 `1:00:00`。 */
    fun clock(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            "$hours:" + minutes.toString().padStart(2, '0') + ":" + seconds.toString().padStart(2, '0')
        } else {
            "$minutes:" + seconds.toString().padStart(2, '0')
        }
    }
}
