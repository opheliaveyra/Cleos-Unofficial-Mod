package com.cleo.cleos.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 气泡里的 Markdown：认什么、不认什么。 */
class MarkdownTest {
    @Test
    fun headingsListsQuotesAndCodeBecomeTheirOwnBlocks() {
        val text = "# 标题\n一段话。\n- 一\n- 二\n1. 先\n2. 后\n> 引用一句\n```kotlin\nval x = 1\n```"
        val blocks = Markdown.blocks(text)
        assertEquals(6, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals("标题", (blocks[0] as MdBlock.Heading).text)
        assertEquals("一段话。", (blocks[1] as MdBlock.Para).text)
        assertEquals(listOf("一", "二"), (blocks[2] as MdBlock.Items).items)
        assertFalse((blocks[2] as MdBlock.Items).ordered)
        assertEquals(listOf("先", "后"), (blocks[3] as MdBlock.Items).items)
        assertTrue((blocks[3] as MdBlock.Items).ordered)
        assertEquals("引用一句", (blocks[4] as MdBlock.Quote).text)
        assertEquals("val x = 1", (blocks[5] as MdBlock.Code).body)
        assertEquals("kotlin", (blocks[5] as MdBlock.Code).lang)
    }

    @Test
    fun plainWordsStayOneParagraph() {
        val blocks = Markdown.blocks("今天天气不错，出去走走？")
        assertEquals(1, blocks.size)
        assertEquals("今天天气不错，出去走走？", (blocks[0] as MdBlock.Para).text)
    }

    @Test
    fun aFenceThatNeverClosesStillShowsWhatWasWritten() {
        // 流式写到一半就是这样：总不能因为没闭合就一个字不显示。
        val blocks = Markdown.blocks("```kotlin\nval x = 1")
        assertEquals(1, blocks.size)
        assertEquals("val x = 1", (blocks[0] as MdBlock.Code).body)
    }

    @Test
    fun aBulletListRightAfterANumberedOneStartsANewList() {
        val blocks = Markdown.blocks("1. 先\n- 一")
        assertEquals(2, blocks.size)
        assertTrue((blocks[0] as MdBlock.Items).ordered)
        assertFalse((blocks[1] as MdBlock.Items).ordered)
    }

    @Test
    fun blankLinesSeparateParagraphs() {
        assertEquals(2, Markdown.blocks("第一段\n\n第二段").size)
    }

    @Test
    fun anEmptyMessageIsStillOneEmptyParagraph() {
        // 不能返回空列表：调用方按"至少有一块"来渲染。
        assertEquals(1, Markdown.blocks("   ").size)
    }
}
