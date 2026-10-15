package com.cleo.cleos.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把一条消息切成"该显示成文字"和"该跑起来"的两部分。
 *
 * 这里只测切分那半（纯函数）。真正把 HTML 跑起来的是 WebView，那个只能真机上看着办 ——
 * 但"**什么时候不该跑**"是能测的，而且那几条恰恰最容易出岔子。
 */
class LiveWidgetsTest {
    @Test
    fun anHtmlFenceIsPickedOutOfTheSentence() {
        val parts = LiveWidgets.split("给你做个小东西：\n\n```html\n<button>点我</button>\n```\n\n试试看。", live = true, cards = true)
        assertEquals(3, parts.size)
        assertTrue((parts[0] as MessagePart.Words).text.contains("给你做个小东西"))
        assertEquals("<button>点我</button>", (parts[1] as MessagePart.Widget).html)
        assertTrue((parts[2] as MessagePart.Words).text.contains("试试看"))
    }

    @Test
    fun otherLanguagesStayAsPlainText() {
        // python 这种不认识：原样留着，别当小组件跑起来。
        val parts = LiveWidgets.split("```python\nprint(1)\n```", live = true, cards = true)
        assertEquals(1, parts.size)
        val words = (parts[0] as MessagePart.Words).text
        assertTrue(words, words.contains("print(1)"))
        assertTrue(words, words.contains("```python"))
    }

    @Test
    fun anUnclosedFenceIsNeverRun() {
        // TA 还在往外写的时候就是这样：半截 HTML 跑起来会闪、会报错，
        // 甚至把写了一半的那版先跑一遍。
        val parts = LiveWidgets.split("看这个：\n```html\n<div>还没写完", live = true, cards = true)
        assertTrue(parts.all { it is MessagePart.Words })
        val words = (parts[0] as MessagePart.Words).text
        assertTrue(words, words.contains("还没写完"))
        assertTrue(words, words.contains("```html"))
    }

    @Test
    fun atMostThreeWidgetsInOneMessage() {
        val four = (1..4).joinToString("\n") { "```html\n<b>$it</b>\n```" }
        val parts = LiveWidgets.split(four, live = true, cards = true)
        assertEquals(3, parts.count { it is MessagePart.Widget })
    }

    @Test
    fun theSwitchOffMeansNothingRunsAtAll() {
        val parts = LiveWidgets.split("```html\n<button>点我</button>\n```", live = false, cards = true)
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MessagePart.Words)
    }

    @Test
    fun aMessageWithNoFenceIsJustWords() {
        val parts = LiveWidgets.split("今天天气不错，出去走走？", live = true, cards = true)
        assertEquals(1, parts.size)
        assertEquals("今天天气不错，出去走走？", (parts[0] as MessagePart.Words).text)
    }

    @Test
    fun aCardWrittenAsJsonStillBecomesACard() {
        // 模型写 JSON 习惯用 ```json。只认 `card` 的话，它写出来的东西会原样变成一段代码，
        // 而人看到的是"它说写了，可我这儿什么都没有" —— 所以 json 围栏也拿去试解析。
        val parts = LiveWidgets.split("```json\n{\"type\":\"choice\",\"options\":[\"甲\",\"乙\"]}\n```", live = false, cards = true)
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MessagePart.Card)
    }

    @Test
    fun aJsonFenceThatIsNotACardStaysCode() {
        val parts = LiveWidgets.split("```json\n{\"name\":\"玖玖\"}\n```", live = false, cards = true)
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MessagePart.Words)
    }

    @Test
    fun aCardThatCannotBeReadSaysSoInsteadOfVanishing() {
        val parts = LiveWidgets.split("```card\n{\"type\":\"choice\" 这儿少了个东西\n```", live = false, cards = true)
        assertEquals(1, parts.size)
        assertTrue("读不懂也要留个痕，不能什么都不显示", parts[0] is MessagePart.Unreadable)
    }

    @Test
    fun cardsStayOffWhenTheirOwnSwitchIsOff() {
        // 卡片和小玩意儿是两个开关：关掉卡片那个，card 就只是普通的代码块。
        val parts = LiveWidgets.split("```card\n{\"type\":\"choice\",\"options\":[\"甲\"]}\n```", live = true, cards = false)
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MessagePart.Words)
    }
}
