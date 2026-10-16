package com.cleo.cleos.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.random.Random

/**
 * 现造一小段视频，用来测"这个模型到底会不会看视频"。
 *
 * **为什么不打包一段素材**：一段能看的 mp4 少说几百 KB，为了测一次把整个 APK 撑大不划算；
 * 而且**固定的素材配固定的暗号，就有被"记住"的可能** —— 每次现画一张、现摇一个码，
 * 才真的能说明它看见了。
 *
 * 做法是最朴素的那条路：**用 MediaCodec 编码几帧灰度图，再用 MediaMuxer 封成 mp4**。
 * 不碰 EGL —— 那要一整套 OpenGL 上下文，为了四帧黑白图不值得。
 * 走 `COLOR_FormatYUV420Flexible` 的输入 Image，直接填 Y 平面（UV 给 128 就是灰度）。
 *
 * 规格刻意压到最小：**320×320、四帧、200 kbps** —— 出来只有几 KB，
 * 发得出去、也快，探针要的是"它有没有看见"，不是画质。
 */
object TestVideo {
    private const val MIME = "video/avc"
    private const val SIZE = 320
    private const val FRAMES = 4
    private const val FPS = 4
    private const val BIT_RATE = 200_000

    /**
     * 写一段 [code] 一直显示在画面上的 mp4 到 [out]。
     *
     * 返回成没成 —— 编码器在某些机器上会挑食（尤其模拟器），失败了要能说清楚，
     * 不能让人对着一个空文件猜。
     */
    fun write(code: String, out: File, random: Random = Random): Boolean {
        val format = MediaFormat.createVideoFormat(MIME, SIZE, SIZE).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val pixel = frame(code, random)
            var queued = 0
            var sawOutputEos = false
            while (!sawOutputEos) {
                // 喂一帧
                if (queued <= FRAMES) {
                    val inIndex = encoder.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val image = encoder.getInputImage(inIndex)
                        if (image == null) {
                            encoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                        } else {
                            fillGray(image, pixel)
                            val flags = if (queued == FRAMES) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            encoder.queueInputBuffer(inIndex, 0, imageSize(), queued * (1_000_000L / FPS), flags)
                            queued++
                        }
                    }
                }
                // 收一帧
                val outIndex = encoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    outIndex >= 0 -> {
                        val buffer = encoder.getOutputBuffer(outIndex)
                        // 配置帧（SPS/PPS）不写进文件，但要等它把 track 建出来。
                        if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            if (muxing && track >= 0) muxer.writeSampleData(track, buffer, info)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
                if (queued > FRAMES && outIndex < 0 && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            return out.isFile && out.length() > 0
        } catch (e: Exception) {
            runCatching { out.delete() }
            return false
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxing) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** 一帧的画面：白底黑字印着 [code]，加几条干扰线 —— 和图片那条一样。 */
    private fun frame(code: String, random: Random): IntArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val noise = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 0, 0, 0)
            strokeWidth = 2f
        }
        repeat(6) {
            canvas.drawLine(
                random.nextInt(SIZE).toFloat(), random.nextInt(SIZE).toFloat(),
                random.nextInt(SIZE).toFloat(), random.nextInt(SIZE).toFloat(), noise,
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = SIZE * 0.30f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
            isFakeBoldText = true
        }
        canvas.drawText(code, SIZE / 2f, SIZE / 2f + paint.textSize * 0.35f, paint)
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        bitmap.recycle()
        return pixels
    }

    /** 把一行像素（灰度）填进 Y 平面；UV 填 128 —— 那是"没有颜色"，出来就是黑白。 */
    private fun fillGray(image: android.media.Image, pixels: IntArray) {
        val y = image.planes[0]
        val buffer = y.buffer
        val rowStride = y.rowStride
        val pixelStride = y.pixelStride
        for (row in 0 until SIZE) {
            var offset = row * rowStride
            for (col in 0 until SIZE) {
                val p = pixels[row * SIZE + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // 常见的亮度权重，人眼看着舒服一点；探针不讲究，够用就行。
                val luma = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255)
                if (offset < buffer.limit()) buffer.put(offset, luma.toByte())
                offset += pixelStride
            }
        }
        for (i in 1 until image.planes.size) {
            val plane = image.planes[i]
            val uv = plane.buffer
            for (offset in 0 until uv.limit()) uv.put(offset, 128.toByte())
        }
    }

    /**
     * 一帧在 YUV420 里占多少字节。
     *
     * **直接用 `宽×高×3/2`**，不去数每个 plane 的 limit：那几个 stride 各家实现不一样，
     * 数错了 `queueInputBuffer` 会直接抛。4:2:0 的亮度 + 四分之一色度就是这个数。
     */
    private fun imageSize(): Int = SIZE * SIZE * 3 / 2
}
