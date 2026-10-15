package com.cleo.cleos.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the chat reads out of a text file, an MD note or a Word document. */
class FileTextTest {
    private fun docx(body: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test
    fun utf8TextIsReadAsItIs() {
        val text = FileText.read("你好，世界\n第二行".toByteArray(Charsets.UTF_8), MessageFile.TXT).text
        assertEquals("你好，世界\n第二行", text)
    }

    @Test
    fun aByteOrderMarkDoesNotBecomeACharacter() {
        val withMark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "标题".toByteArray(Charsets.UTF_8)
        assertEquals("标题", FileText.read(withMark, MessageFile.TXT).text)
    }

    @Test
    fun aFileNameOnAChineseWindowsMachineIsReadAsGbk() {
        // "备忘录" in GBK: not valid UTF-8, which is how the app knows to try the older code page.
        val gbk = byteArrayOf(0xB1.toByte(), 0xB8.toByte(), 0xCD.toByte(), 0xFC.toByte(), 0xC2.toByte(), 0xBC.toByte())
        assertEquals("备忘录", FileText.read(gbk, MessageFile.TXT).text)
    }

    @Test
    fun markdownKeepsItsLinesAndLosesItsCarriageReturns() {
        val text = FileText.read("# 标题\r\n\r\n- 一\r\n- 二\r\n".toByteArray(), MessageFile.MD).text
        assertEquals("# 标题\n\n- 一\n- 二", text)
    }

    @Test
    fun aDocxGoesThroughTheWordReader() {
        val text = FileText.read(docx("<w:p><w:r><w:t>会议记录</w:t></w:r></w:p>"), MessageFile.DOCX).text
        assertEquals("会议记录", text)
    }

    @Test
    fun aKindTheAppDoesNotReadIsRefused() {
        assertThrows(FileException::class.java) { FileText.read(byteArrayOf(1, 2, 3), "png") }
    }

    @Test
    fun textLongerThanTheCapIsCutRatherThanRefused() {
        val long = "字".repeat(FileText.MAX_CHARS + 500)
        assertEquals(FileText.MAX_CHARS, FileText.read(long.toByteArray(Charsets.UTF_8), MessageFile.TXT).text.length)
    }

    @Test
    fun arunOfBlankLinesIsTidiedUp() {
        val text = FileText.read("第一段\n\n\n\n\n第二段".toByteArray(), MessageFile.TXT).text
        assertEquals("第一段\n\n第二段", text)
    }

    @Test
    fun aDamagedWordFileSaysWhyRatherThanReturningNothing() {
        val error = assertThrows(ImportException::class.java) { FileText.read("不是压缩包".toByteArray(), MessageFile.DOCX) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().isNotEmpty())
    }

    @Test
    fun anEmptyTextFileComesBackEmptyRatherThanFailing() {
        assertEquals("", FileText.read(ByteArray(0), MessageFile.TXT).text)
    }
}
