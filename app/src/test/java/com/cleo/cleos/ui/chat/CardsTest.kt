package com.cleo.cleos.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片就是"它只说结构，App 负责画"。所以这里测的全是**读**那一半：
 * 读得出来就画，读不出来就当普通文字 —— 它写错一个逗号，不该让整条消息炸掉。
 */
class CardsTest {
    @Test
    fun aChoiceCardComesOutAsItsOptions() {
        val card = Cards.parse("""{"type":"choice","title":"今晚吃什么","options":["火锅","烧烤","随便"]}""")
        assertTrue(card is ChatCard.Choice)
        card as ChatCard.Choice
        assertEquals("今晚吃什么", card.title)
        assertEquals(listOf("火锅", "烧烤", "随便"), card.options)
    }

    @Test
    fun aConfirmCardFallsBackToPlainLabels() {
        val card = Cards.parse("""{"type":"confirm","title":"要清掉吗"}""") as ChatCard.Confirm
        assertEquals("好", card.yes)
        assertEquals("算了", card.no)
    }

    @Test
    fun anInputCardCarriesItsPlaceholderAndSendLabel() {
        val card = Cards.parse("""{"type":"input","title":"想听什么","placeholder":"歌名…","send":"就它"}""") as ChatCard.Ask
        assertEquals("歌名…", card.placeholder)
        assertEquals("就它", card.send)
    }

    @Test
    fun aListCardReadsBothAStringAndAnObjectPerItem() {
        val card = Cards.parse("""{"type":"list","title":"出门带这些","items":["钥匙",{"title":"伞","detail":"可能下雨"}]}""") as ChatCard.Notes
        assertEquals(2, card.items.size)
        assertEquals("钥匙", card.items[0].title)
        assertNull(card.items[0].detail)
        assertEquals("可能下雨", card.items[1].detail)
    }

    @Test
    fun theTypeMayBeGuessedFromWhatIsThere() {
        // 它忘了写 type 的时候也认：有 options 就是选择题，有 items 就是清单。
        assertTrue(Cards.parse("""{"title":"挑一个","options":["甲","乙"]}""") is ChatCard.Choice)
        assertTrue(Cards.parse("""{"title":"清单","items":["一"]}""") is ChatCard.Notes)
    }

    @Test
    fun brokenJsonIsNothingRatherThanAThrow() {
        assertNull(Cards.parse("这不是 JSON"))
        assertNull(Cards.parse("""{"type":"choice","options":[]}"""))
        assertNull(Cards.parse("""{"type":"choice" """))
        assertNull(Cards.parse(""))
    }

    @Test
    fun somethingThatIsNotOneOfTheFourIsRefused() {
        assertNull(Cards.parse("""{"type":"video","src":"x.mp4"}"""))
        assertNull(Cards.parse("""{"type":"list","items":[]}"""))
    }

    @Test
    fun tooManyOptionsAreCutRatherThanShown() {
        val many = (1..20).joinToString(",") { "\"选项$it\"" }
        val card = Cards.parse("""{"type":"choice","options":[$many]}""") as ChatCard.Choice
        assertEquals(6, card.options.size)
    }

    @Test
    fun aCardInsideAMessageBecomesItsOwnPart() {
        val text = "你先挑：\n\n```card\n{\"type\":\"choice\",\"options\":[\"甲\",\"乙\"]}\n```\n\n挑完告诉我。"
        val parts = LiveWidgets.split(text, live = true, cards = true)
        assertEquals(3, parts.size)
        assertTrue(parts[0] is MessagePart.Words)
        assertTrue(parts[1] is MessagePart.Card)
        assertTrue(parts[2] is MessagePart.Words)
    }

    @Test
    fun aCardFenceThatNeverClosesIsNotDrawn() {
        val parts = LiveWidgets.split("挑一个：\n```card\n{\"type\":\"choice\"", live = true, cards = true)
        assertTrue(parts.all { it is MessagePart.Words })
    }
}
