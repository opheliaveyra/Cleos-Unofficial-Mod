package com.cleo.cleos.ui.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cleo.cleos.glass.LocalGlassPalette

/** 一条消息切开的样子：普通文字，或者一个跑起来的小玩意儿。 */
sealed interface MessagePart {
    data class Words(val text: String) : MessagePart
    data class Widget(val html: String) : MessagePart
}

/**
 * 这一屏要不要把 TA 写的小玩意儿跑起来（设置里的开关）。
 *
 * 走 CompositionLocal 而不是一路当参数传：消息气泡在很里面，中间隔着好几层，
 * 为了一个布尔值把整条链都改一遍不值得。
 */
val LocalLiveWidgets = androidx.compose.runtime.compositionLocalOf { false }

/**
 * 把一条消息切成该显示的样子。
 *
 * 只认识这几种围栏；别的语言（```python 之类）原样留着，继续当代码看。
 * **没闭合的围栏也算普通文字**：TA 还在流式往外写的时候，半截 HTML 就跑起来会闪、
 * 会报错，甚至把写了一半的版本先跑一遍 —— 等它写完再跑。
 */
object LiveWidgets {
    /** 一条消息里最多跑几个：每个都是一个 WebView，LazyColumn 里尤其贵。 */
    const val MAX_PER_MESSAGE = 3

    private val LANGS = setOf("html", "htm", "widget")

    fun split(text: String, enabled: Boolean): List<MessagePart> {
        if (!enabled || !text.contains("```")) return listOf(MessagePart.Words(text))
        val out = mutableListOf<MessagePart>()
        val plain = StringBuilder()
        val body = StringBuilder()
        var open: String? = null
        var widgets = 0

        fun flushPlain() {
            val words = plain.toString().trim()
            plain.setLength(0)
            if (words.isNotEmpty()) out += MessagePart.Words(words)
        }

        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (open == null) {
                val lang = if (trimmed.startsWith("```")) trimmed.removePrefix("```").trim().lowercase() else null
                if (lang != null && lang in LANGS && widgets < MAX_PER_MESSAGE) {
                    open = lang
                    body.setLength(0)
                    continue
                }
                plain.append(line).append('\n')
            } else if (trimmed == "```") {
                flushPlain()
                out += MessagePart.Widget(body.toString().trim())
                widgets++
                open = null
            } else {
                body.append(line).append('\n')
            }
        }
        // 没闭合：把围栏连同里面的东西原样还回去，别当小组件跑。
        if (open != null) plain.append("```").append(open).append('\n').append(body)
        flushPlain()
        return out.ifEmpty { listOf(MessagePart.Words(text)) }
    }
}

/** 一条消息的正文：该是文字的是文字，该跑起来的是小玩意儿。 */
@Composable
internal fun MessageBody(text: String, color: Color, style: TextStyle, widgets: Boolean, modifier: Modifier = Modifier) {
    val parts = remember(text, widgets) { LiveWidgets.split(text, widgets) }
    if (parts.size == 1 && parts[0] is MessagePart.Words) {
        MarkdownText((parts[0] as MessagePart.Words).text, ink = color, style = style, modifier = modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is MessagePart.Words -> MarkdownText(part.text, ink = color, style = style)
                is MessagePart.Widget -> LiveWidget(part.html, color)
            }
        }
    }
}

/**
 * TA 在对话里做的小玩意儿：它写的 HTML 直接在气泡里跑起来，能点、能玩。
 *
 * 和"代码块"是两回事 —— 那个只是把代码排好看，这个是真的跑。所以边界得自己划：
 *
 * · **不许联网**（blockNetworkLoads）—— 它做的东西不能往外发请求，也加载不了外面的图；
 * · **不许碰文件**（allowFileAccess / allowContentAccess 关掉）—— 读不到这台手机上的东西；
 * · **不许跳出去**（shouldOverrideUrlLoading 一律拦下）—— 点什么都留在这个框里；
 * · **不挂 JS 桥**（没有 addJavascriptInterface）—— 页面里的脚本够不着 App 的任何东西。
 *
 * 也就是说：它能在这一小块地方里折腾，但伸不到外面。开关在设置里，默认关着。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LiveWidget(html: String, ink: Color, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current.density
    var heightPx by remember(html) { mutableIntStateOf(0) }
    var failure by remember(html) { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (heightPx > 0) (heightPx / density).dp else 140.dp)
                .clip(RoundedCornerShape(14.dp)),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    webViewClient = object : WebViewClient() {
                        // 点什么都不许跳出去：这是给人玩的一小块地方，不是浏览器。
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                        @Deprecated("Kept for older WebViews")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

                        override fun onPageFinished(view: WebView, url: String?) {
                            // 让框子跟着内容长：量一次文档高度，换算成 dp。
                            view.evaluateJavascript("document.documentElement.scrollHeight") { value ->
                                val px = value?.trim('"')?.toFloatOrNull()?.toInt() ?: 0
                                if (px > 0) heightPx = px.coerceIn(60, 2400)
                            }
                        }
                    }
                }
            },
            update = { web ->
                if (web.tag as? String == html) return@AndroidView
                web.tag = html
                runCatching { web.loadDataWithBaseURL(null, page(html, ink, palette.contentSecondary), "text/html", "utf-8", null) }
                    .onFailure { failure = it.message }
            },
        )
        failure?.let { Text("这个小玩意儿没能跑起来：$it", color = palette.contentSecondary, fontSize = 11.sp) }
    }
}

/** 把 TA 写的一段东西套成一张页面：透明底、跟着气泡的颜色走、不许横向溢出。 */
private fun page(body: String, ink: Color, secondary: Color): String = """
    <!doctype html>
    <html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <style>
      html, body { margin: 0; padding: 6px; background: transparent;
        color: ${hex(ink)}; font-size: 14px; line-height: 1.5;
        font-family: -apple-system, "Noto Sans SC", sans-serif; overflow-x: hidden;
        -webkit-user-select: none; user-select: none; }
      button { font: inherit; color: inherit; background: ${hex(ink)}1a;
        border: 1px solid ${hex(ink)}33; border-radius: 10px; padding: 8px 14px; margin: 3px; }
      button:active { background: ${hex(ink)}33; }
      input, select { font: inherit; color: inherit; background: transparent;
        border: 1px solid ${hex(ink)}44; border-radius: 8px; padding: 6px 8px; }
      * { max-width: 100%; }
    </style>
    </head><body>$body</body></html>
""".trimIndent()

/** 颜色写成 #rrggbb：页面得被告知，它自己看不见 App 的调色板。 */
private fun hex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())
