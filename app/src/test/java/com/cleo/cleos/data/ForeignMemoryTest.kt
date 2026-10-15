package com.cleo.cleos.data

import com.cleo.cleos.ai.MemoryKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Third-party memory files: a JSON array, one wrapped in an object, or plain text / Markdown. */
class ForeignMemoryTest {
    @Test
    fun aJsonArrayOfStringsIsReadAsProfileTopics() {
        val out = ForeignMemory.parse("""["喜欢喝咖啡","住在上海"]""")
        assertEquals(2, out.size)
        assertEquals(listOf("profile", "profile"), out.map { it.kind })
        assertEquals("喜欢喝咖啡", out[0].name)
        assertEquals("住在上海", out[1].name)
    }

    @Test
    fun aJsonObjectWrappingTheArrayKeepsNameAndDetails() {
        val out = ForeignMemory.parse(
            """{"memories":[{"name":"称呼","summary":"喜欢被叫小名","details":["a","b"]}]}""",
        )
        assertEquals(1, out.size)
        assertEquals("称呼", out[0].name)
        assertEquals("喜欢被叫小名", out[0].summary)
        assertEquals(2, out[0].details.size)
    }

    @Test
    fun aSectionIsOneMemoryNotOneLine() {
        // 空行分节，节里的几行合成一条。一行一条的话，一份写成清单的人设文档导进来
        // 就是几百条碎渣：列表翻不完，TA 查到了也看不出前后讲的是同一件事。
        val out = ForeignMemory.parse("称呼: 喜欢被叫小名\n\n- 喜欢喝咖啡\n- 住在上海")
        assertEquals(2, out.size)
        assertEquals("称呼", out[0].name)
        assertEquals("喜欢被叫小名", out[0].summary)
        assertTrue(out[1].name.startsWith("喜欢喝咖啡"))
        assertTrue(out[1].summary.contains("住在上海"))
    }

    @Test
    fun aChecklistDocumentComesOutAsSectionsNotOneMemoryPerLine() {
        val text = buildString {
            appendLine("# 外貌")
            repeat(10) { appendLine("- 外貌第 $it 条") }
            appendLine()
            appendLine("# 性格")
            repeat(10) { appendLine("- 性格第 $it 条") }
        }
        val out = ForeignMemory.parse(text)
        assertEquals(2, out.size)
        val first = out[0].summary + "\n" + out[0].details.joinToString("\n")
        val second = out[1].summary + "\n" + out[1].details.joinToString("\n")
        assertTrue(first, first.contains("外貌第 0 条"))
        assertTrue(first, first.contains("外貌第 9 条"))
        assertTrue(second, second.contains("性格第 0 条"))
        assertTrue(second, second.contains("性格第 9 条"))
    }

    @Test
    fun oneLineWithNothingToSplitIsStillAMemory() {
        // No JSON reader would call this JSON, but a bare line of words reads as a literal: it is
        // a text file with one thing in it, not a file with nothing in it.
        val out = ForeignMemory.parse("喜欢喝咖啡")
        assertEquals(1, out.size)
        assertEquals("喜欢喝咖啡", out[0].name)
        assertEquals("喜欢喝咖啡", out[0].summary)
    }

    @Test
    fun aFileCannotWriteLongerThanTheMemoryKeeps() {
        // The summary is the one line the TA reads with every message, so a paragraph that
        // doesn't fit keeps its head there and the whole of it as details.
        val paragraph = "甲".repeat(400)
        val one = ForeignMemory.parse(paragraph).single()
        assertEquals(MemoryKinds.SUMMARY_MAX, one.summary.length)
        assertEquals(paragraph, one.details.joinToString(""))
        // A long name is cut the same way, and a long detail with it.
        val long = ForeignMemory.parse("""[{"name":"${"乙".repeat(90)}","summary":"短","details":["${"丙".repeat(900)}"]}]""").single()
        assertEquals(MemoryKinds.NAME_MAX, long.name.length)
        assertEquals(MemoryKinds.DETAIL_MAX, long.details.single().length)
        // Past how many details a topic holds, the rest are left out.
        val items = (1..80).joinToString(",", "[", "]") { "\"第${it}条\"" }
        val many = ForeignMemory.parse("""[{"name":"一条","summary":"短","details":$items}]""").single()
        assertEquals(MemoryKinds.DETAILS, many.details.size)
    }
}
