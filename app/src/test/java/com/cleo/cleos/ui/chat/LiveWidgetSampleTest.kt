package com.cleo.cleos.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段一（字符串 → 解析）：确认 ```html 围栏里的内容**一个字符都没丢**。
 * 样例按要求带：标题、两段文字、按钮、输入框、长内容。
 */
class LiveWidgetSampleTest {
    private val sample = listOf(
        "<!doctype html>",
        "<div style=\"background:#a78bfa;padding:14px;border-radius:14px\">",
        "  <h2>今日推荐</h2>",
        "  <p>第一段：这是第一段文字，用来验证段落之后的内容还在不在。</p>",
        "  <p>第二段：这是第二段文字。</p>",
        "  <button onclick=\"this.textContent='点过了'\">点我</button>",
        "  <input placeholder=\"输入点什么\">",
        "  <div>长内容开始：很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。很长的一段内容。长内容结束。</div>",
        "</div>",
    ).joinToString("\n")

    @Test
    fun theWholeHtmlBlockSurvivesSplitting() {
        val text = "前言\n" + "```html\n" + sample + "\n```\n后记"
        val parts = LiveWidgets.split(text, live = true, cards = true)
        val widget = parts.filterIsInstance<MessagePart.Widget>().single()
        assertEquals(sample.trim(), widget.html)
        assertTrue("标题要还在", widget.html.contains("今日推荐"))
        assertTrue("第一段要还在", widget.html.contains("第一段"))
        assertTrue("第二段要还在", widget.html.contains("第二段"))
        assertTrue("按钮要还在", widget.html.contains("<button"))
        assertTrue("输入框要还在", widget.html.contains("<input"))
        assertTrue("长内容结尾要还在", widget.html.contains("长内容结束"))
    }

    @Test
    fun aFenceThatIsNotClosedDoesNotBecomeAWidget() {
        val text = "前言\n```html\n<h1>半截</h1>\n没闭合"
        val parts = LiveWidgets.split(text, live = true, cards = true)
        assertTrue(parts.none { it is MessagePart.Widget })
    }
}
