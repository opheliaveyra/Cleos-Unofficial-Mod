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

    @Test
    fun fullWidthSpacesAndSymbolsStillMakeAList() {
        // 中文输入法下打出来的空格是全角（　）、项目符号是全角（•）——
        // 只认半角的话，**中文里写的列表一个都认不出来**：加粗和引用看着正常，
        // 列表却是一行行原文，人看到的就是"别的东西不显示"。
        val blocks = Markdown.blocks("　•　第一条\n　•　第二条")
        assertEquals(1, blocks.size)
        assertEquals(listOf("第一条", "第二条"), (blocks[0] as MdBlock.Items).items)
    }

    @Test
    fun aNumberedListWithAChineseCommaCounts() {
        val blocks = Markdown.blocks("1、先做这个\n2、再做那个")
        assertEquals(1, blocks.size)
        assertEquals(listOf("先做这个", "再做那个"), (blocks[0] as MdBlock.Items).items)
        assertTrue((blocks[0] as MdBlock.Items).ordered)
    }

    @Test
    fun aChineseQuoteMarkCountsToo() {
        assertTrue(Markdown.blocks("》这句是引用")[0] is MdBlock.Quote)
    }

    @Test
    fun aTableComesOutAsHeaderAndRows() {
        val blocks = Markdown.blocks("| 甲 | 乙 |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |")
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("甲", "乙"), table.header)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), table.rows)
    }

    @Test
    fun aTableWithoutOuterPipesStillCounts() {
        // 有的人（和有些模型）不写两头的竖线，那也是表格。
        val blocks = Markdown.blocks("甲 | 乙\n--- | ---\n1 | 2")
        assertTrue(blocks[0] is MdBlock.Table)
        assertEquals(listOf("甲", "乙"), (blocks[0] as MdBlock.Table).header)
    }

    @Test
    fun theSeparatorMayHaveColons() {
        assertTrue(Markdown.blocks("| 甲 | 乙 |\n|:---|:---:|\n| 1 | 2 |")[0] is MdBlock.Table)
    }

    @Test
    fun aSinglePipeIsNotATable() {
        // 「甲|乙」只有一根竖线，是普通句子 —— 认成表格比不渲染更糟。
        assertTrue(Markdown.blocks("甲|乙")[0] is MdBlock.Para)
        assertTrue(Markdown.blocks("用 A|B 表示二者之一")[0] is MdBlock.Para)
    }

    @Test
    fun aTableEndsWhereItsRowsEnd() {
        val blocks = Markdown.blocks("| 甲 |\n| --- |\n| 1 |\n\n后面这段话")
        assertEquals(2, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Table).rows.size)
        assertTrue(blocks[1] is MdBlock.Para)
    }

    @Test
    fun aRowWithFewerCellsIsKept() {
        // 模型写表格经常最后一行少一根竖线，为这个把整张表丢掉不值得。
        val table = Markdown.blocks("| 甲 | 乙 |\n| --- | --- |\n| 1 |")[0] as MdBlock.Table
        assertEquals(1, table.rows.size)
        assertEquals(listOf("1"), table.rows[0])
    }

    @Test
    fun aHeadingRightAfterATableStillWorks() {
        val blocks = Markdown.blocks("| 甲 |\n| --- |\n| 1 |\n# 标题")
        assertEquals(2, blocks.size)
        assertTrue(blocks[1] is MdBlock.Heading)
    }

    @Test
    fun aPipeInsideACodeFenceIsNotATable() {
        val blocks = Markdown.blocks("```\n| 甲 | 乙 |\n| --- | --- |\n```")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Code)
    }
}
