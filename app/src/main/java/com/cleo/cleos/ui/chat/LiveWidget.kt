package com.cleo.cleos.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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

/** 诊断标签：`adb logcat -s CleosWidget`。 */
private const val TAG = "CleosWidget"

/** 卡片标题上限：超过就截断，别把卡片撑成两行。 */
private const val TITLE_MAX = 28

/**
 * TA 在对话里做的小玩意儿。
 *
 * **聊天里只放一张卡片**（标题 + 类型 + 「打开查看」），不内嵌 WebView：
 * 气泡宽度有限，HTML 在那个尺寸里必然被裁，看起来像「内容没了」。
 *
 * 点开之后是独立的全屏查看器（[WidgetViewer]）：完整宽高、可以滚、可以点、可以输入，
 * 键盘弹起时内容自己让位。安全策略没变（不联网、不碰文件、不跳出去、没有 JS 桥），
 * 状态还是靠同一段 HTML 的 localStorage（[stableKey]），关了再开尽量回到原样。
 */
@Composable
internal fun LiveWidget(html: String, ink: Color, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    var open by remember(html) { mutableStateOf(false) }
    val title = remember(html) { widgetTitle(html) }

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { open = true },
        style = palette.card,
        shape = GlassShape.Rounded(18.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("小玩意儿", color = palette.contentSecondary, fontSize = 11.sp)
                Text(title, color = palette.content, fontSize = 15.sp, maxLines = 1)
                Text("HTML · 可交互", color = palette.contentSecondary, fontSize = 11.sp)
            }
            TextButton(onClick = { open = true }) {
                Text("打开查看", color = palette.accentContent, fontSize = 13.sp)
            }
        }
    }

    if (open) WidgetViewer(html = html, title = title, ink = ink, onClose = { open = false })
}

/**
 * 独立的全屏查看器。
 *
 * 和之前那个 80% 弹窗的区别：占满整块屏，WebView 直接 fillMaxSize，
 * **不再按内容量高度**，长页面交给 WebView 自己滚；键盘弹起时用 imePadding 让内容让位。
 */
@Composable
private fun WidgetViewer(html: String, title: String, ink: Color, onClose: () -> Unit) {
    val palette = LocalGlassPalette.current
    var resetCount by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 返回手势 / 返回键先关查看器，不要退出聊天。
        BackHandler(enabled = true) { onClose() }

        // 键盘：让对话框窗口自己 resize，imePadding 才有东西可让。
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            runCatching {
                dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            onDispose { }
        }

        // 查看器要盖住聊天，背景必须不透明：按 palette.content 的明暗决定用纸色还是深夜色。
        val onLight = palette.content.luminance() < 0.5f
        val viewerBg = if (onLight) Color(0xFFFBF8F3) else Color(0xFF15131A)
        Surface(modifier = Modifier.fillMaxSize(), color = viewerBg) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("小玩意儿", color = palette.contentSecondary, fontSize = 11.sp)
                        Text(title, color = palette.content, fontSize = 15.sp, maxLines = 1)
                    }
                    TextButton(onClick = { resetCount++ }) {
                        Text("重置状态", color = palette.contentSecondary, fontSize = 12.sp)
                    }
                    TextButton(onClick = onClose) {
                        Text("关闭", color = palette.content, fontSize = 13.sp)
                    }
                }
                FullScreenWidget(
                    html = html,
                    ink = ink,
                    resetSignal = resetCount,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

/** 全屏这块 WebView：不测量内容高度，直接吃掉剩下的所有空间，内部自己滚。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FullScreenWidget(
    html: String,
    ink: Color,
    resetSignal: Int,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val stateKey = remember(html) { stableKey(html) }
    var failure by remember(html) { mutableStateOf<String?>(null) }

    val view = remember(html) {
        makeWebView(
            context = context,
            html = html,
            ink = ink,
            secondary = palette.contentSecondary,
            stateKey = stateKey,
            onFailure = { failure = it },
        )
    }
    DisposableEffect(view) {
        onDispose { runCatching { view.stopLoading(); view.destroy() } }
    }
    // 「重置状态」：清掉这段 HTML 的 localStorage 再重载，别的 HTML 的存档不动。
    LaunchedEffect(view, resetSignal) {
        if (resetSignal > 0) {
            view.evaluateJavascript(
                "try{window.__cleosNoSave=true}catch(e){};" +
                "try{localStorage.removeItem('cleos.widget." + stateKey + "')}catch(e){};" +
                "location.reload();",
                null,
            )
        }
    }

    Column(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { view },
            update = {},
        )
        failure?.let {
            Text("这个小玩意儿没能跑起来：" + it, color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}

/**
 * 建一个 WebView：安全策略全在这儿 —— 不联网、不碰文件、不许跳出去、没有 JS 桥。
 * 状态靠 Web Storage：同一个 HTML 的输入/滚动存进它自己的 localStorage（origin 按 stateKey 隔离）。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun makeWebView(
    context: Context,
    html: String,
    ink: Color,
    secondary: Color,
    stateKey: String,
    onFailure: (String?) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(AndroidColor.TRANSPARENT)
    // 全屏查看器里要能点进输入框、弹出键盘。
    isFocusable = true
    isFocusableInTouchMode = true
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    runCatching { CookieManager.getInstance().setAcceptCookie(false) }
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.blockNetworkLoads = true
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    isVerticalScrollBarEnabled = true
    isHorizontalScrollBarEnabled = false
    webViewClient = object : WebViewClient() {
        // 点什么都不许跳出去：这是给人玩的一小块地方，不是浏览器。
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        @Deprecated("Kept for older WebViews")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

        override fun onPageFinished(view: WebView, url: String?) {
            Log.d(TAG, "loaded html=" + html.length)
        }
    }
    // 固定 origin（按 stateKey 区分）：页面拿到的 localStorage 是按这段 HTML 隔离的；
    // blockNetworkLoads 已经禁止联网，这个假域名只用来给存储分区，不指向任何真实服务。
    val origin = "https://cleos.widget/" + stateKey + "/"
    runCatching { loadDataWithBaseURL(origin, page(html, ink, secondary, stateKey), "text/html", "utf-8", null) }
        .onFailure { onFailure(it.message) }
}

/** 从 HTML 里挑一个能当卡片标题的东西：title 标签，没有就退到一句通用的。 */
private fun widgetTitle(html: String): String {
    val raw = runCatching {
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)
    }.getOrNull()?.replace(Regex("\\s+"), " ")?.trim()
    return when {
        raw.isNullOrEmpty() -> "HTML 小玩意儿"
        raw.length > TITLE_MAX -> raw.take(TITLE_MAX) + "…"
        else -> raw
    }
}

/** 把 TA 写的一段东西套成一张页面：透明底、跟着聊天配色走、不许横向溢出。 */
private fun page(body: String, ink: Color, secondary: Color, stateKey: String): String {
    val inkHex = hex(ink)
    val style = """
      html, body { margin: 0; padding: 10px; background: transparent;
        color: ${inkHex}; font-size: 14px; line-height: 1.5;
        font-family: -apple-system, "Noto Sans SC", sans-serif;
        height: auto; min-height: 0 !important; }
      html { height: 100%; }
      body { min-height: 100%; overflow-y: auto; overflow-x: hidden;
        -webkit-overflow-scrolling: touch; }
      div, section, article, main, header, footer, aside, form { overflow: visible !important; }
      button { font: inherit; color: inherit; background: ${inkHex}1a;
        border: 1px solid ${inkHex}33; border-radius: 10px; padding: 8px 14px; margin: 3px; }
      button:active { background: ${inkHex}33; }
      input, select, textarea { font: inherit; color: inherit; background: transparent;
        border: 1px solid ${inkHex}44; border-radius: 8px; padding: 6px 8px; }
      * { max-width: 100%; }
    """
    return "<!doctype html><html><head>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">" +
        "<style>" + style + "</style></head><body>" + body +
        "<script>" + stateScript(stateKey) + "</script></body></html>"
}

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
    if (window.__cleosNoSave) return;
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
