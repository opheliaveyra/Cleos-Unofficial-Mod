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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

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

/**
 * 一次探测的结论。
 *
 * **四种状态必须分开**，混在一起会骗人：
 * - [PASSED]：答对了暗号 —— 这个能力是真的
 * - [UNSUPPORTED]：**接口明确说**不支持这种输入 —— 这才是「没有这个能力」
 * - [FAILED]：请求没走通（网络、Key、地址、请求格式）—— **不能**据此说模型不支持
 * - [UNVERIFIED]：请求成功，但它没答对 / 什么都没答 —— 待验证
 */
enum class ProbeOutcome { PASSED, UNSUPPORTED, FAILED, UNVERIFIED }

/** 一次探测的结果。 */
data class ProbeResult(
    val passed: Boolean,
    val message: String,
    /** 排查用的脱敏细节：HTTP 状态、服务端回的原文、请求结构。**不含 Key 和完整 base64**。 */
    val detail: String? = null,
    val outcome: ProbeOutcome = if (passed) ProbeOutcome.PASSED else ProbeOutcome.FAILED,
)

class CapabilityTester(
    private val images: ImageStore,
    private val videos: com.cleo.cleos.data.VideoStore,
    private val secrets: SecretStore,
    private val client: ChatClient,
) {

    /**
     * Real tool-calling probe. The nonce is only in the user request, and the
     * function schema requires an exact echo. No real device tool is executed.
     */
    suspend fun testTools(baseUrl: String, model: String): ProbeResult {
        val key = secrets.key(baseUrl)?.takeIf { it.isNotBlank() }
            ?: return ProbeResult(false, "没有 API Key，无法验证工具调用", outcome = ProbeOutcome.FAILED)
        val nonce = CapabilityProbe.newCode()
        val spec = ToolSpec(
            name = "cleos_capability_echo",
            groups = emptySet(),
            action = "回传测试码",
            description = "Return the supplied code through the code argument. Do not write a normal chat response.",
            parameters = kotlinx.serialization.json.buildJsonObject {
                put("type", "object")
                put("properties", kotlinx.serialization.json.buildJsonObject {
                    put("code", kotlinx.serialization.json.buildJsonObject {
                        put("type", "string")
                        put("description", "The exact four-character code")
                    })
                })
                put("required", kotlinx.serialization.json.buildJsonArray { add("code") })
                put("additionalProperties", false)
            },
        )
        return try {
            var calls = emptyList<ToolCall>()
            client.stream(
                ApiEndpoint(baseUrl, key, model),
                listOf(ApiMessage("user", "Call cleos_capability_echo with code $nonce. Do not answer in text.")),
                tools = listOf(spec),
            ).collect { event ->
                if (event is ChatEvent.ToolCalls) calls = calls + event.calls
            }
            val valid = calls.any { call ->
                if (call.name != spec.name) false
                else runCatching {
                    val args = kotlinx.serialization.json.Json.parseToJsonElement(call.arguments).jsonObject
                    args["code"]?.jsonPrimitive?.content == nonce
                }.getOrDefault(false)
            }
            if (valid) ProbeResult(true, "工具调用通过：模型正确返回测试工具及参数", outcome = ProbeOutcome.PASSED)
            else ProbeResult(false, "请求结束，但没有收到参数正确的测试工具调用", outcome = ProbeOutcome.UNVERIFIED)
        } catch (e: ChatException) {
            val unsupported = looksUnsupported(e)
            ProbeResult(false, e.message?.take(160) ?: "工具调用请求失败",
                outcome = if (unsupported) ProbeOutcome.UNSUPPORTED else ProbeOutcome.FAILED)
        } catch (e: Exception) {
            ProbeResult(false, "工具调用验证失败：${e.message?.take(160)}", outcome = ProbeOutcome.FAILED)
        }
    }

    suspend fun testVideo(profile: ModelProfile): ProbeResult = testVideo(profile.baseUrl, profile.model)

    /** 同上，但按「地址 + 型号」来 —— 三层结构里这两件事拆开了，见 [ModelConfig]。 */
    suspend fun testVideo(baseUrl: String, model: String): ProbeResult {
        val key = secrets.key(baseUrl)?.takeIf { it.isNotBlank() }
            ?: return ProbeResult(false, "这个地址还没有 API Key —— 先在「服务商」里填上", outcome = ProbeOutcome.FAILED)
        val code = CapabilityProbe.newCode()
        val probe = java.io.File(videos.dir, "probe-${java.util.UUID.randomUUID()}.mp4")
        val made = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { TestVideo.write(code, probe) }.getOrDefault(false)
        }
        if (!made) {
            probe.delete()
            return ProbeResult(
                false,
                "测试视频没能造出来 —— 这台机器的视频编码器挑食（模拟器上常见）。换个办法：在聊天里真发一条几秒的视频。",
                outcome = ProbeOutcome.FAILED,
            )
        }
        val url = videos.dataUrl(probe.name)
        if (url == null) {
            probe.delete()
            return ProbeResult(false, "测试视频读不出来", outcome = ProbeOutcome.FAILED)
        }
        val shape = videoRequestShape(baseUrl, model, url.length)
        val declared = vendorDeclaresVideo(model)
        return try {
            val said = StringBuilder()
            client.stream(
                ApiEndpoint(baseUrl, key, model),
                listOf(ApiMessage("user", CapabilityProbe.PROMPT_VIDEO, videos = listOf(url))),
            ).collect { if (it is ChatEvent.Delta) said.append(it.text) }
            val text = said.toString().trim()
            when {
                CapabilityProbe.matches(text, code) -> ProbeResult(
                    true,
                    "看得见视频：画面里的码是 $code，它答对了",
                    detail = shape,
                    outcome = ProbeOutcome.PASSED,
                )
                text.isEmpty() -> ProbeResult(
                    false,
                    "请求成功了，但它一个字都没回 —— 这次不算数，再测一次。" +
                        if (declared) "（小米官方文档写明这个型号支持视频理解，所以更像这次没送进去。）" else "",
                    detail = shape,
                    outcome = ProbeOutcome.UNVERIFIED,
                )
                else -> ProbeResult(
                    false,
                    "请求成功，但它没认出视频里的码（正确答案 $code，它回的是「${text.take(60)}」）—— " +
                        if (declared) {
                            "官方文档说这个型号支持视频理解，这更像请求格式或这次没送进去的问题，不是它不会。"
                        } else {
                            "这个模型多半看不了视频，换一个（豆包 Seed、通义 Qwen、小米 MiMo、Gemini 都行）。"
                        },
                    detail = shape,
                    outcome = ProbeOutcome.UNVERIFIED,
                )
            }
        } catch (e: ChatException) {
            val unsupported = looksUnsupported(e)
            ProbeResult(
                false,
                if (unsupported) {
                    "接口明确说它不支持视频输入：${e.message?.take(160)}"
                } else {
                    "请求没走通（不是「模型不会看」）：${e.message?.take(160)}" +
                        if (declared) " —— 官方文档写明这个型号支持视频，先查网络 / Key / 格式。" else ""
                },
                detail = diagnostics(e, shape),
                outcome = if (unsupported) ProbeOutcome.UNSUPPORTED else ProbeOutcome.FAILED,
            )
        } catch (e: Exception) {
            ProbeResult(
                false,
                "解析或网络出错（不是「模型不会看」）：${e.message?.take(160) ?: e.javaClass.simpleName}",
                detail = diagnostics(e, shape),
                outcome = ProbeOutcome.FAILED,
            )
        } finally {
            probe.delete()
        }
    }
    /**
     * 测这个配置会不会看图。
     *
     * 每一条失败路径都**说清楚是什么问题**（没 Key / 服务不通 / 答错 / 解析失败），
     * 因为「测试失败」三个字帮不了任何人。
     */
    suspend fun testImage(profile: ModelProfile): ProbeResult = testImage(profile.baseUrl, profile.model)

    /** 同上，但按「地址 + 型号」来（见 [testVideo]）。 */
    suspend fun testImage(baseUrl: String, model: String): ProbeResult {
        val key = secrets.key(baseUrl)?.takeIf { it.isNotBlank() }
            ?: return ProbeResult(false, "这个地址还没有 API Key —— 先在「服务商」里填上", outcome = ProbeOutcome.FAILED)
        val code = CapabilityProbe.newCode()
        val name = runCatching { images.save(CapabilityProbe.image(code), prefix = "probe-") }.getOrNull()
            ?: return ProbeResult(false, "测试图没能存下来（存储空间不够？）", outcome = ProbeOutcome.FAILED)
        val shape = imageRequestShape(baseUrl, model)
        return try {
            val url = images.dataUrl(name)
            if (url == null) return ProbeResult(false, "测试图没能读出来", outcome = ProbeOutcome.FAILED)
            val said = StringBuilder()
            client.stream(
                ApiEndpoint(baseUrl, key, model),
                listOf(ApiMessage("user", CapabilityProbe.PROMPT, images = listOf(url))),
            ).collect { if (it is ChatEvent.Delta) said.append(it.text) }
            val text = said.toString().trim()
            when {
                CapabilityProbe.matches(text, code) -> ProbeResult(true, "看得见图：图里的码是 $code，它答对了", detail = shape, outcome = ProbeOutcome.PASSED)
                text.isEmpty() -> ProbeResult(false, "请求成功了，但它一个字都没回 —— 再测一次。", detail = shape, outcome = ProbeOutcome.UNVERIFIED)
                else -> ProbeResult(
                    false,
                    "它没能认出图里的码（正确答案 $code，它回的是「${text.take(60)}」）—— 这个模型多半只看文字，换一个能看图的给它。",
                    detail = shape,
                    outcome = ProbeOutcome.UNVERIFIED,
                )
            }
        } catch (e: ChatException) {
            val unsupported = looksUnsupported(e)
            ProbeResult(
                false,
                if (unsupported) "接口明确说它不支持图片输入：${e.message?.take(160)}" else "请求没走通（不是「模型不会看」）：${e.message?.take(160)}",
                detail = diagnostics(e, shape),
                outcome = if (unsupported) ProbeOutcome.UNSUPPORTED else ProbeOutcome.FAILED,
            )
        } catch (e: Exception) {
            ProbeResult(
                false,
                "解析或网络出错（不是「模型不会看」）：${e.message?.take(160) ?: e.javaClass.simpleName}",
                detail = diagnostics(e, shape),
                outcome = ProbeOutcome.FAILED,
            )
        } finally {
            images.delete(listOf(name))
        }
    }

    /**
     * 请求长什么样 —— **只写结构，不写内容**。
     *
     * 视频那一大段 base64 只报长度（几 MB，也绝不该出现在日志里）；Key 在请求头里，这里根本没有。
     */
    private fun videoRequestShape(baseUrl: String, model: String, payloadChars: Int): String =
        "POST https://${hostOf(baseUrl)}/chat/completions\n" +
            "model=$model\n" +
            "stream=true\n" +
            "content=[{type=video_url, video_url={url=data:video/mp4;base64,[$payloadChars 字符，已省略]}},{type=text, text=<暗号提示>}]\n" +
            "（只发 video_url；fps / media_resolution 是小米专有字段，按通用写法没放，免得别家 400）"

    private fun imageRequestShape(baseUrl: String, model: String): String =
        "POST https://${hostOf(baseUrl)}/chat/completions\n" +
            "model=$model\n" +
            "content=[{type=image_url, image_url={url=data:image/png;base64,[已省略]}},{type=text, text=<暗号提示>}]"

    private fun hostOf(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: baseUrl.trim()

    /** 失败时留给排查的那一页：状态码 + 服务端原文（脱敏）+ 请求结构。 */
    private fun diagnostics(e: Throwable, shape: String): String = buildString {
        val ce = e as? ChatException
        ce?.status?.let { append("HTTP ").append(it).append("\n") }
        append(shape).append("\n")
        val body = ce?.responseBody
        if (!body.isNullOrBlank()) append("服务端回：").append(body) else append("错误：").append(e.message?.take(300) ?: e.javaClass.simpleName)
    }

    /**
     * 服务端是不是**明确说**了「不支持这种输入」。
     *
     * 只看明确的措辞；「模型名不存在」「格式不对」「余额不足」都不算 ——
     * 那些是请求的问题，不是能力的问题。
     */
    private fun looksUnsupported(e: Throwable): Boolean =
        probeSaysUnsupported((e as? ChatException)?.responseBody, e.message)
}

/**
 * 厂商文档明确写了「这个型号支持视频理解」吗（小米 MiMo V2.6 系列，见官方文档）。
 *
 * 用来把话说清楚：**测试失败 ≠ 模型不支持**。官方写明了的话，失败更像是
 * 请求格式 / 网络 / 解析的问题，不能说成「它不会看」。
 */
internal fun vendorDeclaresVideo(model: String): Boolean =
    model.trim().lowercase().startsWith("mimo-v2.6")

/**
 * 服务端是不是**明确说**了「不支持这种输入」。
 *
 * 只看明确的措辞；「模型名不存在」「格式不对」「余额不足」都不算 ——
 * 那些是请求的问题，不是能力的问题。抽成纯函数是为了能单独测。
 */
internal fun probeSaysUnsupported(responseBody: String?, message: String?): Boolean {
    val text = ((responseBody ?: "") + "\n" + (message ?: "")).lowercase()
    if (text.isBlank()) return false
    return listOf(
        "not support", "unsupported", "does not support", "doesn't support", "not supported",
        "不支持", "no video", "video not allowed",
    ).any { it in text }
}
