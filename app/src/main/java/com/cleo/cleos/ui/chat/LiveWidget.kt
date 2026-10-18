package com.cleo.cleos.ui.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette

/** 一条消息切开的样子：普通文字、跑起来的小玩意儿，或者一张画出来的卡片。 */
sealed interface MessagePart {
    data class Words(val text: String) : MessagePart
    /** 跑起来的：它写的 HTML（LiveWidget）。 */
    data class Widget(val html: String) : MessagePart
    /** 画出来的：它只说"要什么"，样式由 App 画（ChatCard）。 */
    data class Card(val json: String) : MessagePart
    /** 它声明了这是卡片，但那段读不懂 —— 说一句，再把原文放过。 */
    data class Unreadable(val json: String) : MessagePart
}

/** 点卡片上某个选项之后该发生什么（把选项当成对方说的那句话送出去）。 */
val LocalCardAnswer = androidx.compose.runtime.compositionLocalOf<(String) -> Unit> { {} }

/**
 * 这一屏要不要把 TA 写的小玩意儿跑起来（设置里的开关）。
 *
 * 走 CompositionLocal 而不是一路当参数传：消息气泡在很里面，中间隔着好几层，
 * 为了一个布尔值把整条链都改一遍不值得。
 */
val LocalLiveWidgets = androidx.compose.runtime.compositionLocalOf { false }

/** 这一屏要不要把 TA 写的卡片画出来。另一个开关，**默认开** —— 画卡片不跑任何代码。 */
val LocalCards = androidx.compose.runtime.compositionLocalOf { true }

/**
 * 把一条消息切成该显示的样子。
 *
 * 只认识这几种围栏；别的语言（```python 之类）原样留着，继续当代码看。
 * **没闭合的围栏也算普通文字**：TA 还在流式往外写的时候，半截 HTML 就跑起来会闪、
 * 会报错，甚至把写了一半的版本先跑一遍 —— 等它写完再跑。
 */
object LiveWidgets {
    /** 一条消息里最多放几个：每个都是一块 WebView 或者一张卡片。 */
    const val MAX_PER_MESSAGE = 3

    /** 跑起来的那种：里面是会执行的 HTML。 */
    private val RAN = setOf("html", "htm", "widget")

    /** 画出来的那种：里面是"要什么"的描述，不执行。 */
    const val CARD_LANG = "card"

    /**
     * 也当卡片试的语言。模型写 JSON 时习惯用 ```json —— 只认 `card` 的话，
     * 它写出来的东西就原样变成一段代码，人看到的是"它说写了，可我这儿什么都没有"。
     * 所以 json 围栏**先拿去试解析**：解得出卡片就画，解不出就还它代码块的样子。
     */
    private val MAYBE_CARD = setOf(CARD_LANG, "json", "card-json")

    fun split(text: String, live: Boolean, cards: Boolean): List<MessagePart> {
        if ((!live && !cards) || !text.contains("```")) return listOf(MessagePart.Words(text))
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
                // 卡片归卡片那个开关管，会跑的归小玩意儿那个开关管 —— 两件事，别混。
                val known = when {
                    lang != null && lang in MAYBE_CARD -> cards
                    lang != null && lang in RAN -> live
                    else -> false
                }
                if (known && widgets < MAX_PER_MESSAGE) {
                    open = lang
                    body.setLength(0)
                    continue
                }
                plain.append(line).append('\n')
            } else if (trimmed == "```") {
                flushPlain()
                val content = body.toString().trim()
                val lang = open!!
                val card = if (lang in MAYBE_CARD) Cards.parse(content) else null
                when {
                    card != null -> out += MessagePart.Card(content)
                    // 声明了 `card` 却读不懂：**别装作没看见**，说一句，再把原文放上。
                    // 不然人看到的是"它说写了，可我这儿什么都没有"。
                    lang == CARD_LANG -> out += MessagePart.Unreadable(content)
                    lang in RAN -> out += MessagePart.Widget(content)
                    // `json` 围栏里不是卡片，那就是一段普通的 JSON —— 还它代码块的样子。
                    else -> out += MessagePart.Words("```$lang\n$content\n```")
                }
                widgets++
                open = null
            } else {
                body.append(line).append('\n')
            }
        }
        // 没闭合：把围栏连同里面的东西原样还回去，别当小组件跑、也别当卡片画。
        open?.let { plain.append("```").append(it).append('\n').append(body) }
        flushPlain()
        return out.ifEmpty { listOf(MessagePart.Words(text)) }
    }
}

/**
 * 消息气泡里的正文：**只有字**。
 *
 * 卡片和小玩意儿不在这儿画 —— 它们在气泡外面单独占一块（[MessageExtras]），
 * 就像工具调用那样。原因是气泡有它的脾气：宽度被 `widthIn(max = …)` 掐着，
 * 里面还要塞玻璃、装饰、长按手势；WebView 那种要按自己方式量尺寸的东西进去，
 * 很可能量出零高度，于是"它说写了，可我这儿什么都没有"。
 */
@Composable
internal fun MessageBody(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val live = LocalLiveWidgets.current
    val cards = LocalCards.current
    // 把文字部分拼回来：卡片和围栏都拿掉，中间断掉的地方补一个换行。
    val words = remember(text, live, cards) {
        LiveWidgets.split(text, live = live, cards = cards)
            .filterIsInstance<MessagePart.Words>()
            .joinToString("\n\n") { it.text }
    }
    if (words.isNotBlank()) MarkdownText(words, ink = color, style = style, modifier = modifier)
}

/**
 * TA 做的东西：**不当气泡里的内容，单独一块**，和工具调用一样。
 *
 * 全宽，不受气泡的宽度限制；能点、能玩、能放大。一段消息里最多三块（[LiveWidgets]）。
 */
@Composable
internal fun MessageExtras(text: String, modifier: Modifier = Modifier) {
    val live = LocalLiveWidgets.current
    val cards = LocalCards.current
    val palette = LocalGlassPalette.current
    val answer = LocalCardAnswer.current
    val extras = remember(text, live, cards) {
        LiveWidgets.split(text, live = live, cards = cards).filter { it !is MessagePart.Words }
    }
    if (extras.isEmpty()) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        extras.forEach { part ->
            when (part) {
                is MessagePart.Words -> Unit
                is MessagePart.Widget -> LiveWidget(part.html, palette.content)
                is MessagePart.Card -> {
                    val card = remember(part.json) { Cards.parse(part.json) }
                    if (card == null) MarkdownText("```${LiveWidgets.CARD_LANG}\n${part.json}\n```", ink = palette.content, style = TextStyle(fontSize = 14.sp))
                    else ChatCardView(card, onAnswer = answer)
                }
                // 声明了是卡片却读不懂：明说，别让人以为"它什么都没写"。
                is MessagePart.Unreadable -> {
                    Text("（这张卡片没读懂 —— 可能是里面少了个引号或者逗号。原文在下面，可以让它重写一次。）",
                        color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                    MarkdownText("```${LiveWidgets.CARD_LANG}\n${part.json}\n```", ink = palette.content, style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                }
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
/** 气泡里最多先给这么高；再长就只在放大里看 —— 但会明确写出来"内容较长"。 */
private val BUBBLE_MAX_HEIGHT = 360.dp

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LiveWidget(html: String, ink: Color, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    var expanded by remember(html) { mutableStateOf(false) }
    var overflows by remember(html) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        WebBox(
            html = html,
            ink = ink,
            modifier = Modifier.fillMaxWidth(),
            maxHeight = BUBBLE_MAX_HEIGHT,
            onOverflow = { overflows = it },
        )
        // 气泡里露出高度有限，得让人知道"后面还有"。
        Text(
            if (overflows) "内容较长，点开放大看全部" else "点开放大",
            color = palette.contentSecondary,
            fontSize = 11.sp,
            modifier = Modifier.clickable { expanded = true },
        )
    }

    if (expanded) {
        // 放大 = **另一个 WebView，重新加载原始 HTML**：不是把气泡里那一小块拉大。
        // 全屏 Dialog、内容自己重新排版，纵向可滚。
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f)
                    .padding(12.dp),
            ) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    style = palette.card,
                    shape = GlassShape.Rounded(24.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("放大看", color = palette.contentSecondary, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { expanded = false }) {
                                Text("收起", color = palette.content)
                            }
                        }
                        // 高度交给父约束，不再固定 420dp —— WebView 自己滚，内容重新排版。
                        WebBox(html, ink, Modifier.fillMaxWidth().weight(1f), maxHeight = Dp.Unspecified)
                    }
                }
            }
        }
    }
}

/** 真正跑起来的那个框子。气泡里一个、放大后一个 —— 两个 WebView 各跑各的。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebBox(
    html: String,
    ink: Color,
    modifier: Modifier = Modifier,
    /** 气泡里给个上限；放大时传 Dp.Unspecified，高度由父约束决定（填满、可滚）。 */
    maxHeight: Dp = Dp.Unspecified,
    onOverflow: (Boolean) -> Unit = {},
) {
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current.density
    var heightPx by remember(html) { mutableIntStateOf(0) }
    var failure by remember(html) { mutableStateOf<String?>(null) }
    val measured = if (heightPx > 0) heightPx / density else 0f
    val grows = maxHeight != Dp.Unspecified && measured > maxHeight.value
    LaunchedEffect(measured, maxHeight) { onOverflow(grows) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (maxHeight == Dp.Unspecified) Modifier.fillMaxHeight()
                    else Modifier.height((if (measured > 0f) minOf(measured, maxHeight.value) else 140f).dp),
                )
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
