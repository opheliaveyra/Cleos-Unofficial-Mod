package com.cleo.cleos.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.cleo.cleos.glass.LocalGlassPalette

/**
 * 聊天气泡里的 Markdown —— 只做**够用的那一小撮**。
 *
 * 为什么要自己做：模型天生会写 Markdown，而 CLEOS 一直让它别写（提示词里明写着"不用
 * Markdown 标题、列表和加粗"），因为**渲染不了，写了就是满屏星号**。既然对话框要开始
 * 渲染了，那就得真的认这些记号。
 *
 * 认：`#` 标题、`-`/`1.` 列表、`>` 引用、``` 代码块、`| a | b |` 表格、`**粗**`、
 * `*斜*`、`` `代码` ``、`~~删除~~`、链接。
 *
 * **表格是后来补的**：TA 特别爱用表格（"我这儿能感觉到的 / 是怎么知道的 / 怎么验"这种
 * 三列的对照），而原来不认 —— 于是它写出来的表格**原样变成一堆竖线**，看着像乱码。
 * 模型爱用的东西就得认，不然等于逼它别写。
 *
 * **不认**：嵌套列表、图片、脚注 —— 聊天里用不上，认多了反而容易把普通句子
 * 误当成语法（一个人真的想打 `*` 的时候，不该被吃掉）。
 */
sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Items(val ordered: Boolean, val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val body: String) : MdBlock

    /** 一张表：[header] 是表头，[rows] 是数据行。列数不齐的会被补齐。 */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

object Markdown {
    /**
     * 空白：半角空格、全角空格（中文输入法下的空格）、不换行空格都算。
     *
     * 这条是补出来的：原来只认半角空格，于是**中文里写出来的列表一个都认不出来** ——
     * 加粗和引用看着正常，列表却是一行行原文，人看到的就是"别的不显示"。
     */
    private const val SP = "[\\s\\u3000\\u00a0]*"

    /** 项目符号也认全角的：`•`、`·`、`‣`、`∙` 都是输入法会打出来的。 */
    private val BULLET = Regex("^$SP[-*+•·‣∙]\\s*(.+)$")
    private val NUMBER = Regex("^$SP(\\d+)[.)、．]\\s*(.+)$")
    private val HEADING = Regex("^$SP(#{1,4})\\s*(.+)$")
    private val QUOTE = Regex("^$SP[>》]\\s?(.*)$")

    /** 分隔行的每一格：`---`、`:--`、`--:`、`:-:` 都算。表格靠它认出来。 */
    private val SEP_CELL = Regex("^:?-{1,}:?$")

    /**
     * 一行表格拆成格子：`| a | b |` → ["a", "b"]。
     *
     * 什么算表格行，这里分两种情况，别搞混：
     *
     * · **两头都写了竖线**（`| 甲 |`）—— 一定是表格，**一列也算**；
     * · **没写两头的竖线**（`甲 | 乙`）—— 要求竖线两边有空格。
     *
     * 第二条是防误伤：`甲|乙` 只有一根竖线、两边没空格，那是**普通句子**
     * （"用 A|B 表示二者之一"这种），把它认成表格比不渲染更糟。
     */
    private fun cells(line: String): List<String>? {
        val t = line.trim()
        if (!t.contains('|')) return null
        val bordered = t.length >= 2 && t.startsWith("|") && t.endsWith("|")
        if (!bordered && !t.contains(" | ")) return null
        val body = if (bordered) t.removePrefix("|").removeSuffix("|") else t
        val parts = body.split('|').map { it.trim() }
        if (parts.isEmpty()) return null
        if (parts.size < 2 && !bordered) return null
        return parts
    }

    /** 分隔行：`| --- | --- |` 这种。它是"上面那行是表头"的信号。 */
    private fun isSeparator(line: String): Boolean {
        val parts = cells(line) ?: return false
        return parts.isNotEmpty() && parts.all { it.matches(SEP_CELL) }
    }

    fun blocks(text: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        val code = StringBuilder()
        var items: MutableList<String>? = null
        var ordered = false
        var quote: MutableList<String>? = null
        var fence: String? = null

        fun flushPara() {
            val s = para.toString().trim()
            para.setLength(0)
            if (s.isNotEmpty()) out += MdBlock.Para(s)
        }

        fun flushItems() {
            items?.let { if (it.isNotEmpty()) out += MdBlock.Items(ordered, it.toList()) }
            items = null
        }

        fun flushQuote() {
            quote?.let { if (it.isNotEmpty()) out += MdBlock.Quote(it.joinToString("\n")) }
            quote = null
        }

        fun flushAll() { flushPara(); flushItems(); flushQuote() }

        val lines = text.split("\n")
        // 表格要一次吃掉好几行，所以用 withIndex + 游标，不能纯 for-each。
        var skipUntil = 0
        for ((i, line) in lines.withIndex()) {
            if (i < skipUntil) continue
            val trimmed = line.trim()
            if (fence != null) {
                if (trimmed.startsWith("```")) {
                    out += MdBlock.Code(fence, code.toString().trimEnd())
                    code.setLength(0)
                    fence = null
                } else {
                    code.append(line).append('\n')
                }
                continue
            }
            if (trimmed.startsWith("```")) { flushAll(); fence = trimmed.removePrefix("```").trim(); continue }
            if (trimmed.isEmpty()) { flushAll(); continue }

            // 表格：这一行是 |…|，下一行是 |---|，那这两行就是表头 + 分隔，接着的都是数据行。
            val headerCells = cells(line)
            if (headerCells != null && i + 1 < lines.size && isSeparator(lines[i + 1])) {
                flushAll()
                val rows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size) {
                    val row = cells(lines[j]) ?: break
                    if (row.all { it.isEmpty() }) break
                    rows.add(row)
                    j++
                }
                out += MdBlock.Table(headerCells, rows)
                skipUntil = j
                continue
            }

            val heading = HEADING.find(line)
            if (heading != null) {
                flushAll()
                out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                continue
            }
            val bullet = BULLET.find(line)
            if (bullet != null) {
                flushPara(); flushQuote()
                if (items == null || ordered) { flushItems(); ordered = false; items = mutableListOf() }
                items!!.add(bullet.groupValues[1].trim())
                continue
            }
            val number = NUMBER.find(line)
            if (number != null) {
                flushPara(); flushQuote()
                if (items == null || !ordered) { flushItems(); ordered = true; items = mutableListOf() }
                items!!.add(number.groupValues[2].trim())
                continue
            }
            val quoted = QUOTE.find(line)
            if (quoted != null) {
                flushPara(); flushItems()
                if (quote == null) quote = mutableListOf()
                quote!!.add(quoted.groupValues[1].trim())
                continue
            }
            flushItems(); flushQuote()
            para.appendLine(trimmed)
        }
        // 没闭合的代码块照样显示：它还在往外写的时候，总得让人看见东西。
        fence?.let { out += MdBlock.Code(it, code.toString().trimEnd()) }
        flushAll()
        return out.ifEmpty { listOf(MdBlock.Para(text.trim())) }
    }

    /** 行内那一层：链接优先（先把它切出去），剩下的认粗体、斜体、行内代码、删除线。 */
    fun annotate(text: String, ink: Color, secondary: Color, onOpen: (String) -> Unit): AnnotatedString = buildAnnotatedString {
        fun plain(part: String) {
            val marks = """(\*\*|__)(.+?)\1|(\*|_)(.+?)\3|(`+)(.+?)\5|~~(.+?)~~""".toRegex()
            var at = 0
            for (m in marks.findAll(part)) {
                if (m.range.first > at) append(part.substring(at, m.range.first))
                when {
                    m.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[2]) }
                    m.groupValues[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[4]) }
                    m.groupValues[6].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = secondary.copy(alpha = 0.22f))) {
                        append(m.groupValues[6])
                    }
                    else -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(m.groupValues[7]) }
                }
                at = m.range.last + 1
            }
            if (at < part.length) append(part.substring(at))
        }

        ChatLinks.parts(text).forEach { part ->
            val url = part.url
            if (url == null) plain(part.text)
            else withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = ink, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium))) { onOpen(url) }) {
                append(part.text)
            }
        }
    }
}

/**
 * 一条消息的正文，按 Markdown 排出来。
 *
 * 一次消息一个 Column，每块自己成形 —— 列表是列表、引用是引用、代码是代码。
 * 这样比在纯文字里插换行清楚得多，而且以后要加图表、表格，加一个分支就行。
 */
@Composable
internal fun MarkdownText(text: String, ink: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val secondary = palette.contentSecondary
    val blocks = remember(text) { Markdown.blocks(text) }
    val open: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }.onFailure {
            Toast.makeText(context, "链接暂时打不开，可以长按消息复制", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    Markdown.annotate(block.text, ink, secondary, open),
                    color = ink,
                    style = style.copy(
                        fontSize = style.fontSize * when (block.level) {
                            1 -> 1.22f
                            2 -> 1.12f
                            3 -> 1.06f
                            else -> 1f
                        },
                        fontWeight = FontWeight.SemiBold,
                    ),
                )

                is MdBlock.Para -> Text(
                    Markdown.annotate(block.text, ink, secondary, open),
                    color = ink,
                    style = style,
                )

                is MdBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                if (block.ordered) "${index + 1}." else "·",
                                color = secondary,
                                style = style,
                                modifier = Modifier.width(20.dp),
                            )
                            Text(
                                Markdown.annotate(item, ink, secondary, open),
                                color = ink,
                                style = style,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .heightIn(min = 18.dp)
                            .background(secondary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                    )
                    Text(
                        Markdown.annotate(block.text, ink, secondary, open),
                        color = secondary,
                        style = style,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                // 代码块里的东西不解析：那段就是原样给人看的。
                is MdBlock.Code -> Text(
                    block.body,
                    color = ink,
                    style = style.copy(fontFamily = FontFamily.Monospace, fontSize = style.fontSize * 0.92f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(secondary.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )

                is MdBlock.Table -> TableView(block, ink, secondary, style, open)
            }
        }
    }
}

/**
 * 一张表。
 *
 * 两个刻意的地方：
 *
 * · **列宽按内容长短分**，不是平均分 —— 平均分的话"是/否"那种短列会白占一大块，
 *   而写得多的那列被挤成一条缝。权重夹在 2～24 之间，免得一列长句子把别人挤没。
 * · **列数不齐的行照样画**，缺的格子留空 —— 模型写表格经常最后一行少一根竖线，
 *   为这个把整张表丢掉不值得。
 */
@Composable
private fun TableView(table: MdBlock.Table, ink: Color, secondary: Color, style: TextStyle, open: (String) -> Unit) {
    val columns = (listOf(table.header.size) + table.rows.map { it.size }).max()
    if (columns == 0) return
    val cellStyle = style.copy(fontSize = style.fontSize * 0.9f, lineHeight = style.lineHeight * 0.95f)
    val weights = (0 until columns).map { c ->
        val longest = (listOf(table.header.getOrNull(c).orEmpty()) + table.rows.map { it.getOrNull(c).orEmpty() })
            .maxOfOrNull { it.length } ?: 1
        longest.coerceIn(2, 24).toFloat()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(secondary.copy(alpha = 0.16f), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp)),
        ) {
            (0 until columns).forEach { c ->
                Text(
                    Markdown.annotate(table.header.getOrNull(c).orEmpty(), ink, secondary, open),
                    color = ink,
                    style = cellStyle.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(weights[c]).padding(horizontal = 7.dp, vertical = 6.dp),
                )
            }
        }
        table.rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(secondary.copy(alpha = 0.06f), RoundedCornerShape(3.dp)),
            ) {
                (0 until columns).forEach { c ->
                    Text(
                        Markdown.annotate(row.getOrNull(c).orEmpty(), ink, secondary, open),
                        color = ink,
                        style = cellStyle,
                        modifier = Modifier.weight(weights[c]).padding(horizontal = 7.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
