package com.cleo.cleos.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.ModelProfile
import com.cleo.cleos.data.SecretStore
import kotlin.random.Random

/**
 * 探一个模型到底会不会看图。
 *
 * 做法照 Operit 的 `MediaCapabilityProbe`：**现画一张印着随机码的图**，让它只回那个码。
 * **码不在提示词里** —— 模型不可能靠猜蒙对，答对了才算它真的看见了。
 *
 * 为什么不看名字就相信它能看图：**GPT 全系收到图会静默丢掉、照样回 200**，
 * 智谱 glm 也会丢，而且 GPT 还会顺着问题编一段根本不存在的画面。
 * 只有"给它一件不做就答不对的小事"才能验出来 —— 这也是 Operit 用这套的理由。
 */
object CapabilityProbe {
    /**
     * 码用的字符。
     *
     * **不含 0/O/1/I/L** —— 这几个在图片里容易看混，模型（和人）都可能抄错，
     * 那样"答错"就分不清是它没看见还是看岔了。
     */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val CODE_LENGTH = 4

    /** 提示词：只回码，别描述 —— 描述得再热闹也可能是在编。 */
    const val PROMPT = "图里有一串字符。只回那串字符本身，不要解释，不要描述图片。"

    /** 视频那条的提示词：码在整段里都显示着。 */
    const val PROMPT_VIDEO = "这段视频里有一串字符，一直都在画面上。只回那串字符本身，不要解释，不要描述视频。"

    fun newCode(random: Random = Random): String =
        (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /**
     * 画一张印着 [code] 的图：白底黑字，加几条随机的浅色斜线。
     *
     * 加干扰线不是为了防机器，是**防"模型的先验"** —— 一张过于干净的纯文字图，
     * 有些模型会半猜半蒙地报一个差不多的；有点干扰反而迫使它真去看。
     */
    fun image(code: String, size: Int = 512, random: Random = Random): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val noise = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 0, 0, 0)
            strokeWidth = 2f
        }
        repeat(6) {
            val x1 = random.nextInt(size).toFloat()
            val y1 = random.nextInt(size).toFloat()
            canvas.drawLine(x1, y1, random.nextInt(size).toFloat(), random.nextInt(size).toFloat(), noise)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = size * 0.30f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
            isFakeBoldText = true
        }
        // 基线要往下挪一点才是"视觉居中"：文字的基线不等于中线。
        canvas.drawText(code, size / 2f, size / 2f + paint.textSize * 0.35f, paint)
        return bitmap
    }

    /**
     * 响应里有没有那个码。
     *
     * 用"包含"而不是"完全等于"：模型常常会写"这个码是 7K2Q"。
     * 宽容一点不会误判 —— **码是现摇的，它瞎猜不可能猜中**；严格相等反而会因为
     * 一句客套话把真会看图的模型判成不会。
     */
    fun matches(response: String, code: String): Boolean =
        alnumUpper(response).contains(alnumUpper(code))

    /** 只留字母数字再转大写；中文和标点都去掉，免得被客套话干扰。 */
    internal fun alnumUpper(text: String): String = buildString(text.length) {
        for (ch in text) if (ch.code < 128 && ch.isLetterOrDigit()) append(ch.uppercaseChar())
    }
}

/** 一次探测的结果。 */
data class ProbeResult(val passed: Boolean, val message: String)
class CapabilityTester(
    private val images: ImageStore,
    private val videos: com.cleo.cleos.data.VideoStore,
    private val secrets: SecretStore,
    private val client: ChatClient,
) {
    /**
     * 测这个配置会不会看视频。
     *
     * 和看图那条同一个套路，只是把"画一张图"换成"**现造一小段视频**"
     * （见 [TestVideo]：四帧灰度图，MediaCodec 编码 + MediaMuxer 封成 mp4，几 KB）。
     * **发的就是真的视频**（`video_url` + base64），走的就是聊天时那条路 ——
     * 测通了才算数，不是拿一帧图片糊弄过去。
     */
    suspend fun testVideo(profile: ModelProfile): ProbeResult = testVideo(profile.baseUrl, profile.model)

    /** 同上，但按「地址 + 型号」来 —— 三层结构里这两件事拆开了，见 [ModelConfig]。 */
    suspend fun testVideo(baseUrl: String, model: String): ProbeResult {
        val key = secrets.key(baseUrl)?.takeIf { it.isNotBlank() }
            ?: return ProbeResult(false, "这个地址还没有 API Key —— 先在「连接」那里填上")
        val code = CapabilityProbe.newCode()
        val probe = java.io.File(videos.dir, "probe-${java.util.UUID.randomUUID()}.mp4")
        val made = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { TestVideo.write(code, probe) }.getOrDefault(false)
        }
        if (!made) {
            probe.delete()
            return ProbeResult(
                false,
                "测试视频没能造出来 —— 这台机器的视频编码器挑食（模拟器上常见）。" +
                    "换个办法：在聊天里真发一条几秒的视频，一样能看出它会不会看。",
            )
        }
        return try {
            val url = videos.dataUrl(probe.name) ?: return ProbeResult(false, "测试视频读不出来")
            val said = StringBuilder()
            client.stream(
                ApiEndpoint(baseUrl, key, model),
                listOf(ApiMessage("user", CapabilityProbe.PROMPT_VIDEO, videos = listOf(url))),
            ).collect { if (it is ChatEvent.Delta) said.append(it.text) }
            val text = said.toString().trim()
            if (CapabilityProbe.matches(text, code)) {
                ProbeResult(true, "看得见视频：画面里的码是 $code，它答对了")
            } else {
                ProbeResult(
                    false,
                    "它没能认出视频里的码（正确答案 $code，它回的是「${text.take(60)}」）——" +
                        "这个模型多半看不了视频，换一个（豆包 Seed、通义 Qwen、小米 MiMo、Gemini 都行）。",
                )
            }
        } catch (e: Exception) {
            ProbeResult(false, "连不上或报错了：${e.message?.take(120) ?: "没有说明"}")
        } finally {
            probe.delete()
        }
    }

    /**
     * 测这个配置会不会看图。
     *
     * 每一条失败路径都**说清楚是什么问题**（没 Key / 服务不通 / 答错），
     * 因为"测试失败"三个字帮不了任何人。
     */
    suspend fun testImage(profile: ModelProfile): ProbeResult = testImage(profile.baseUrl, profile.model)

    /** 同上，但按「地址 + 型号」来（见 [testVideo]）。 */
    suspend fun testImage(baseUrl: String, model: String): ProbeResult {
        val key = secrets.key(baseUrl)?.takeIf { it.isNotBlank() }
            ?: return ProbeResult(false, "这个地址还没有 API Key —— 先在「连接」那里填上")
        val code = CapabilityProbe.newCode()
        val name = runCatching { images.save(CapabilityProbe.image(code), prefix = "probe-") }.getOrNull()
            ?: return ProbeResult(false, "测试图没能存下来（存储空间不够？）")
        return try {
            val url = images.dataUrl(name)
                ?: return ProbeResult(false, "测试图没能读出来")
            val said = StringBuilder()
            client.stream(
                ApiEndpoint(baseUrl, key, model),
                listOf(ApiMessage("user", CapabilityProbe.PROMPT, images = listOf(url))),
            ).collect { if (it is ChatEvent.Delta) said.append(it.text) }
            val text = said.toString().trim()
            if (CapabilityProbe.matches(text, code)) {
                ProbeResult(true, "看得见图：图里的码是 $code，它答对了")
            } else {
                ProbeResult(
                    false,
                    "它没能认出图里的码（正确答案 $code，它回的是「${text.take(60)}」）——" +
                        "这个模型多半只看文字，换一个能看图的给它。",
                )
            }
        } catch (e: Exception) {
            ProbeResult(false, "连不上或报错了：${e.message?.take(120) ?: "没有说明"}")
        } finally {
            images.delete(listOf(name))
        }
    }
}
