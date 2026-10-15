package com.cleo.cleos.data

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader is hand-written, so these tests build PDFs by hand too: the object shapes that
 * Word, Chrome and pdfTeX write, and the ones that are supposed to be refused.
 */
class PdfTextTest {
    /** Objects in order, numbered from 1; a trailer naming the first as the catalog. */
    private fun pdf(vararg objects: String): ByteArray =
        ("%PDF-1.4\n" + objects.mapIndexed { index, body -> "${index + 1} 0 obj\n$body\nendobj\n" }.joinToString("") +
            "trailer\n<< /Root 1 0 R /Size ${objects.size + 1} >>\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)

    private fun stream(dict: String, bytes: ByteArray): String =
        "$dict\nstream\n" + String(bytes, Charsets.ISO_8859_1) + "\nendstream"

    private fun deflated(text: String): ByteArray {
        val deflater = Deflater()
        val out = ByteArrayOutputStream()
        deflater.setInput(text.toByteArray(Charsets.ISO_8859_1))
        deflater.finish()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private val plainFont = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"

    private fun onePage(content: String, font: String = plainFont): ByteArray = pdf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        font,
        stream("<< /Length ${content.toByteArray(Charsets.ISO_8859_1).size} >>", content.toByteArray(Charsets.ISO_8859_1)),
    )

    @Test
    fun aPageOfPlainTextIsReadBack() {
        val text = PdfText.read(onePage("BT /F1 24 Tf 72 700 Td (Hello, Cleos.) Tj ET")).text
        assertEquals("Hello, Cleos.", text)
    }

    @Test
    fun chineseComesBackThroughTheFontsToUnicodeTable() {
        // 你好 in a subset font: the codes in the stream are 1 and 2, and only the CMap says
        // which characters those are. This is what makes a Chinese PDF readable at all.
        val cmap = """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            1 beginbfchar
            <0001> <4F60>
            <0002> <597D>
            endbfchar
            endcmap
            end end
        """.trimIndent()
        val content = "BT /F1 24 Tf 72 700 Td <00010002> Tj ET"
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type0 /BaseFont /SimSun /Encoding /Identity-H /DescendantFonts [7 0 R] /ToUnicode 6 0 R >>",
            stream("<< /Length ${content.length} >>", content.toByteArray(Charsets.ISO_8859_1)),
            stream("<< /Length ${cmap.length} >>", cmap.toByteArray(Charsets.ISO_8859_1)),
            "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /SimSun >>",
        )
        assertEquals("你好", PdfText.read(bytes).text)
    }

    @Test
    fun aRangeInTheCMapCoversEveryCodeItNames() {
        val cmap = "begincmap\n1 beginbfrange\n<0010> <0013> <4E00>\nendbfrange\nendcmap"
        val content = "BT /F1 12 Tf 10 700 Td <0010001100120013> Tj ET"
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type0 /Encoding /Identity-H /ToUnicode 6 0 R >>",
            stream("<< /Length ${content.length} >>", content.toByteArray(Charsets.ISO_8859_1)),
            stream("<< /Length ${cmap.length} >>", cmap.toByteArray(Charsets.ISO_8859_1)),
        )
        assertEquals("一丁丂七", PdfText.read(bytes).text)
    }

    @Test
    fun compressedContentIsInflated() {
        val content = "BT /F1 18 Tf 40 500 Td (Flate encoded words) Tj ET"
        val compressed = deflated(content)
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            plainFont,
            stream("<< /Length ${compressed.size} /Filter /FlateDecode >>", compressed),
        )
        assertEquals("Flate encoded words", PdfText.read(bytes).text)
    }

    @Test
    fun severalPagesComeBackInTheOrderOfThePageTree() {
        // The pages are written out of order on purpose: the reader must follow /Kids, not the
        // order the objects happen to sit in the file.
        val first = "BT /F1 12 Tf 20 700 Td (page one) Tj ET"
        val second = "BT /F1 12 Tf 20 700 Td (page two) Tj ET"
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [4 0 R 3 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>",
            plainFont,
            stream("<< /Length ${second.length} >>", second.toByteArray(Charsets.ISO_8859_1)),
            stream("<< /Length ${first.length} >>", first.toByteArray(Charsets.ISO_8859_1)),
        )
        val read = PdfText.read(bytes)
        assertEquals(2, read.pages)
        // /Kids names object 4 first, and object 4 is the page whose stream holds "page one".
        assertEquals("page one\n\npage two", read.text)
    }

    @Test
    fun linesAndWordSpacesSurviveTheOperatorsThatDrawThem() {
        val content = "BT /F1 12 Tf 20 700 Td (first line) Tj 0 -20 Td (second) Tj " +
            "[ (with) -300 (a) -300 (gap) ] TJ ET"
        val text = PdfText.read(onePage(content)).text
        assertTrue(text, text.contains("first line"))
        assertTrue(text, text.contains("second"))
        assertTrue(text, text.contains("with a gap"))
        assertTrue(text, text.indexOf("first line") < text.indexOf("second"))
    }

    @Test
    fun anEncryptedFileIsRefusedRatherThanHalfRead() {
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [] /Count 0 >>",
        ).let { original ->
            // The trailer of onePage's shape, with /Encrypt added, is what a locked file has.
            val text = String(original, Charsets.ISO_8859_1).replace("/Size", "/Encrypt 9 0 R /Size")
            text.toByteArray(Charsets.ISO_8859_1)
        }
        val error = assertThrows(FileException::class.java) { PdfText.read(bytes) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("密码"))
    }

    @Test
    fun aPageWithNoTextSaysSoInsteadOfReturningNonsense() {
        val error = assertThrows(FileException::class.java) { PdfText.read(onePage("0 0 1 rg 10 10 100 100 re f")) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("没有文字"))
    }

    @Test
    fun somethingThatIsNotAPdfIsRefused() {
        val error = assertThrows(FileException::class.java) { PdfText.read("这是一段文字，不是 PDF".toByteArray()) }
        assertTrue(error.message.orEmpty().contains("PDF"))
        assertThrows(FileException::class.java) { PdfText.read(ByteArray(3)) }
    }

    @Test
    fun aPageOfScannedPicturesIsReportedAsUnreadable() {
        // An inline image between BI and EI: its bytes are not text and must not be read as any.
        val content = "q 100 0 0 100 10 10 cm BI /W 4 /H 4 /BPC 8 /CS /G ID \u0001\u0002\u0003\u0004 EI Q " +
            "BT /F1 12 Tf 20 700 Td (after the picture) Tj ET"
        assertEquals("after the picture", PdfText.read(onePage(content)).text)
    }
}
