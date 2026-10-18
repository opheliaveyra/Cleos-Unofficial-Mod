package com.cleo.cleos.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
/** 两种模式的上限：都取可用屏幕的 80%。 */
private const val MODE_RATIO = 0.8f

/** 诊断标签：`adb logcat -s CleosWidget`。排查完可以整段删掉。 */
private const val TAG = "CleosWidget"

/**
 * 聊天里的小玩意儿。**两种模式，不共用同一套截断/交互**：
 *
 * · 预览（[WidgetPreview]）：宽最多 80% 屏宽、高最多 80% 屏高，按内容自适应；
 *   超出就裁掉，**不滚动、不消费触摸**（聊天列表照常滚），按钮也点不动；
 * · 放大（[WidgetZoom]）：同一个 HTML 重新加载，宽高最多 80% 屏，内容超出时内部可滚，
 *   按钮/输入框正常可用。
 */
@Composable
internal fun LiveWidget(html: String, ink: Color, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    var expanded by remember(html) { mutableStateOf(false) }
    var clipped by remember(html) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        WidgetPreview(html, ink, onClipped = { clipped = it })
        // 被裁的时候说清楚「后面还有」，别让人以为就这么多。
        Text(
            if (clipped) "内容较长，点开放大看全部" else "点开放大",
            color = palette.contentSecondary,
            fontSize = 11.sp,
            modifier = Modifier.clickable { expanded = true },
        )
    }
    if (expanded) {
        WidgetZoom(html, ink, onClose = { expanded = false })
    }
}

/** 模式一：聊天里的预览。宽 80% 屏、高按内容（最多 80% 屏），**不滚动、不消费触摸**。 */
@Composable
private fun WidgetPreview(html: String, ink: Color, onClipped: (Boolean) -> Unit) {
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    WidgetBox(
        html = html,
        ink = ink,
        modifier = Modifier.fillMaxWidth().widthIn(max = screenW * MODE_RATIO),
        maxHeight = screenH * MODE_RATIO,
        interactive = false,
        onClipped = onClipped,
    )
}

/** 模式二：放大。同一个 HTML 重新加载；宽高最多 80% 屏，超了内部自己滚，交互正常。 */
@Composable
private fun WidgetZoom(html: String, ink: Color, onClose: () -> Unit) {
    val palette = LocalGlassPalette.current
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    var resetCount by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            // 弹窗本身按内容走：宽度最多 80% 屏，高度由里面的 WebView 决定（≤80% 屏）。
            Column(
                Modifier
                    .widthIn(max = screenW * MODE_RATIO)
                    .wrapContentHeight(),
            ) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    style = palette.card,
                    shape = GlassShape.Rounded(24.dp),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Column(Modifier.wrapContentHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("放大看", color = palette.contentSecondary, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { resetCount++ }) {
                                Text("重置状态", color = palette.contentSecondary, fontSize = 12.sp)
                            }
                            TextButton(onClick = onClose) { Text("收起", color = palette.content) }
                        }
                        WidgetBox(
                            html = html,
                            ink = ink,
                            modifier = Modifier.fillMaxWidth(),
                            maxHeight = screenH * MODE_RATIO,
                            interactive = true,
                            resetSignal = resetCount,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 两种模式共用的「测量 + 容器」：高度从小往上长（**只增不减**），最多到 [maxHeight]。
 * 超过上限的部分由 WebView 视口裁掉 —— 预览不滚；放大模式 WebView 自己滚。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WidgetBox(
    html: String,
    ink: Color,
    modifier: Modifier = Modifier,
    maxHeight: Dp,
    interactive: Boolean,
    /** 放大弹窗的「重置状态」：每 +1 清一次本 key 的 localStorage 并重载页面。 */
    resetSignal: Int = 0,
    onClipped: (Boolean) -> Unit = {},
) {
    val palette = LocalGlassPalette.current
    // 注意：WebView 里 getBoundingClientRect() 给的是 CSS px；页面 viewport 是 width=device-width，
    // 所以 1 CSS px = 1dp（**不要再除 density**，除一次内容就会只剩 1/3，看起来就是被裁）。
    val context = LocalContext.current
    val floor = 72f
    var measuredPx by remember(html) { mutableIntStateOf(0) }
    var failure by remember(html) { mutableStateOf<String?>(null) }
    var heightDp by remember(html) { mutableFloatStateOf(floor) }
    LaunchedEffect(measuredPx, maxHeight) {
        if (measuredPx > 0) {
            val want = measuredPx.toFloat().coerceAtMost(maxHeight.value).coerceAtLeast(floor)
            // 只增不减：视口变大会让 min-height:100vh 的元素变高，来回缩会震荡。
            if (want > heightDp + 1f) heightDp = want
        }
    }
    val clipped = heightDp >= maxHeight.value - 1f
    LaunchedEffect(clipped) { onClipped(clipped) }

    val stateKey = remember(html) { stableKey(html) }
    val view = remember(html, interactive) {
        makeWebView(
            context, html, ink, palette.contentSecondary, interactive,
            stateKey = stateKey,
            onHeight = { px -> measuredPx = px },
            onFailure = { failure = it },
        )
    }
    DisposableEffect(view) {
        onDispose { runCatching { view.stopLoading(); view.destroy() } }
    }
    LaunchedEffect(view, resetSignal) {
        if (resetSignal > 0) {
            view.evaluateJavascript(
                "try{localStorage.removeItem('cleos.widget." + stateKey + "')}catch(e){};location.reload();",
                null,
            )
        }
    }

    Column(modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.coerceAtLeast(floor).dp)
                .clip(RoundedCornerShape(14.dp)),
            factory = { view },
            update = {},
        )
        failure?.let { Text("这个小玩意儿没能跑起来：$it", color = palette.contentSecondary, fontSize = 11.sp) }
    }
}

/**
 * 建一个 WebView：安全策略全在这儿 —— 不联网、不碰文件、不许跳出去、没有 JS 桥。
 * `onHeight` 在加载完成和图片/字体加载后各喂一次高度（有上限，不会无限循环）。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun makeWebView(
    context: Context,
    html: String,
    ink: Color,
    secondary: Color,
    /** false = 预览（不消费触摸）；true = 放大（可滚、可交互）。 */
    interactive: Boolean,
    /** 这段 HTML 的状态作用域（见 [stableKey]）：同一段 HTML 的 localStorage 共享。 */
    stateKey: String,
    onHeight: (Int) -> Unit,
    onFailure: (String?) -> Unit,
): WebView = (if (interactive) WebView(context) else PreviewWebView(context)).apply {
    setBackgroundColor(AndroidColor.TRANSPARENT)
    // 放大模式下要能点进输入框、弹出键盘（预览模式仍由 PreviewWebView 挡住触摸）。
    isFocusable = true
    isFocusableInTouchMode = true
    settings.javaScriptEnabled = true
    // 状态恢复靠 Web Storage：同一个 HTML 的输入/滚动存到它自己的 localStorage（origin 按 stateKey 隔离）。
    settings.domStorageEnabled = true
    runCatching { CookieManager.getInstance().setAcceptCookie(false) }
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.blockNetworkLoads = true
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    // 放大才允许框内滚动；预览模式不消费触摸（见 PreviewWebView），滚动交给聊天列表。
    isVerticalScrollBarEnabled = interactive
    isHorizontalScrollBarEnabled = interactive
    webViewClient = object : WebViewClient() {
        // 点什么都不许跳出去：这是给人玩的一小块地方，不是浏览器。
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        @Deprecated("Kept for older WebViews")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

        override fun onPageFinished(view: WebView, url: String?) {
            measureOnce(view, html, onHeight)
            // 图片、字体加载完高度会变：再量两次（固定次数，防死循环）。
            view.postDelayed({ measureOnce(view, html, onHeight) }, 350)
            view.postDelayed({ measureOnce(view, html, onHeight) }, 1200)
            view.postDelayed({ measureOnce(view, html, onHeight) }, 2200)
            // 有些页面 3 秒后才长内容（动画/延迟脚本）：再补一次，别让它一直露一半。
            view.postDelayed({ measureOnce(view, html, onHeight) }, 4000)
        }
    }
    // 固定 origin（按 stateKey 区分）：页面拿到的 localStorage 是按这段 HTML 隔离的；
    // blockNetworkLoads 已经禁止联网，这个假域名只用来给存储分区，不指向任何真实服务。
    val origin = "https://cleos.widget/" + stateKey + "/"
    runCatching { loadDataWithBaseURL(origin, page(html, ink, secondary, stateKey), "text/html", "utf-8", null) }
        .onFailure { onFailure(it.message) }
}

/**
 * 量内容高度。**不能用 `scrollHeight`**：WebView 视口已经很大时（放大框），
 * `scrollHeight` 恒等于视口高 —— 量出来永远是「满屏」，弹窗就缩不下来。
 *
 * 也不能直接把所有元素的 bottom 取最大：模型常写 `min-height:100vh` 的容器，
 * 它的 bottom 就是视口底 —— 一样会量成满屏。
 *
 * 所以：撑满视口（≥ 视口 90%）的**容器**跳过，其余元素（含卡片本身、叶子节点）
 * 的 bottom 取最大 —— 卡片高度真实就用卡片，卡片被撑满就只看里面的文字/按钮。
 */
private fun measureOnce(view: WebView, html: String, onHeight: (Int) -> Unit) {
    view.evaluateJavascript(
        "(function(){var b=document.body;if(!b)return 0;" +
            "var vh=window.innerHeight||1;var maxB=0;" +
            "var els=b.querySelectorAll('*');" +
            "for(var i=0;i<els.length;i++){var el=els[i];var r=el.getBoundingClientRect();" +
            "if(!(r.width>0&&r.height>0))continue;" +
            "if(el.children&&el.children.length>0&&r.height>=vh*0.9)continue;" +
            "if(r.bottom>maxB)maxB=r.bottom;}" +
            "if(maxB<=0)maxB=b.getBoundingClientRect().height;" +
            "return Math.ceil(maxB+10);})()",
    ) { value ->
        val px = value?.trim('"')?.toFloatOrNull()?.toInt() ?: 0
        if (px > 0) {
            Log.d(TAG, "measure srcHtml=" + html.length + " px=" + px)
            onHeight(px.coerceIn(60, 6000))
        }
    }
}

/**
 * 预览用的 WebView：**不消费任何触摸事件** —— 聊天列表照常滚，预览里的按钮也点不动。
 * 这是「模式一不执行交互」的实现方式：不截断 HTML，只挡触摸。
 */
private class PreviewWebView(context: Context) : WebView(context) {
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
}

/** 把 TA 写的一段东西套成一张页面：透明底、跟着气泡的颜色走、不许横向溢出。 */
private fun page(body: String, ink: Color, secondary: Color, stateKey: String): String = """
    <!doctype html>
    <html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <style>
      html, body { margin: 0; padding: 6px; background: transparent;
        color: ${hex(ink)}; font-size: 14px; line-height: 1.5;
        font-family: -apple-system, "Noto Sans SC", sans-serif;
        -webkit-user-select: none; user-select: none; height: auto; min-height: 0 !important; }
      /* 只压 html/body 的最小高度（模型常写 min-height:100vh 把页面撑满）；
         **不要**去压 body 的一级子元素 —— 那会把模型给卡片设的 min-height 也压掉，
         卡片塌缩、内容反而被裁。 */
      /* 宽内容：先响应式压缩，压不下去就允许横向滚，别静默裁掉。 */
      body { overflow-x: auto; overflow-y: visible; }
      table { max-width: none; }
      /* 兜底：模型常写「固定高度 + overflow:hidden」的卡片，长内容就被裁掉、
         看起来像「后面没了」。只让溢出可见，不改它自己的高度和布局。 */
      div, section, article, main, header, footer, aside, form { overflow: visible !important; }
      button { font: inherit; color: inherit; background: ${hex(ink)}1a;
        border: 1px solid ${hex(ink)}33; border-radius: 10px; padding: 8px 14px; margin: 3px; }
      button:active { background: ${hex(ink)}33; }
      input, select { font: inherit; color: inherit; background: transparent;
        border: 1px solid ${hex(ink)}44; border-radius: 8px; padding: 6px 8px; }
      * { max-width: 100%; }
    </style>
    </head><body>$body
    <script>${stateScript(stateKey)}</script>
    </body></html>
""".trimIndent()

/** 颜色写成 #rrggbb：页面得被告知，它自己看不见 App 的调色板。 */
private fun hex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())

/**
 * 同一段 HTML（同一条消息里的同一个小玩意儿）→ 同一个状态作用域。
 * 内容变了就是另一份状态 —— 旧存档自然隔离，不会串到新版本上。
 */
internal fun stableKey(html: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(html.toByteArray())
    return digest.take(10).joinToString("") { "%02x".format(it) }
}

/**
 * 状态脚本：表单值（text/textarea/select/checkbox）和滚动位置存进本 origin 的 localStorage，
 * 重新打开同一个 HTML 时恢复。**只恢复这些可识别的 UI 状态** —— JS 闭包、定时器、
 * Canvas/WebGL 像素不在能力范围内（报告里写清楚）。
 */
private fun stateScript(stateKey: String): String = """
(function(){
  var KEY = 'cleos.widget.$stateKey';
  var restored = false;
  function els(){ return document.querySelectorAll('input,textarea,select'); }
  function save(){
    try {
      var list = els(), vals = [];
      for (var i = 0; i < list.length; i++) {
        var e = list[i];
        vals.push(e.type === 'checkbox' || e.type === 'radio' ? (e.checked ? '1' : '0') : e.value);
      }
      localStorage.setItem(KEY, JSON.stringify({ v: vals, scroll: window.scrollY || 0 }));
    } catch (e) {}
  }
  function restore(){
    if (restored) return;
    restored = true;
    var raw = null;
    try { raw = localStorage.getItem(KEY); } catch (e) { return; }
    if (!raw) return;
    var data = null;
    try { data = JSON.parse(raw); } catch (e) { return; }
    var list = els();
    for (var i = 0; i < list.length && data.v && i < data.v.length; i++) {
      var e = list[i];
      if (e.type === 'checkbox' || e.type === 'radio') e.checked = data.v[i] === '1';
      else if (e.type !== 'file') e.value = data.v[i];
    }
    if (data.scroll) window.scrollTo(0, data.scroll);
  }
  document.addEventListener('input', save, true);
  document.addEventListener('change', save, true);
  window.addEventListener('scroll', save, true);
  restore();
  setTimeout(restore, 300);
})();
""".trimIndent()
