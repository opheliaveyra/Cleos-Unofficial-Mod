package com.cleo.cleos.data

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 真实软件产出的 PDF：用 JVM PDFBox **只生成样本**（testImplementation，不进 APK），
 * 被测的是 App 自己的 [PdfText]。四类：纯文字、中文、混合图文、扫描图片。
 */
class PdfRealWorldTest {
    private val chineseFont = File("C:" + File.separator + "Windows" + File.separator + "Fonts" + File.separator + "simhei.ttf")

    private fun save(doc: PDDocument): ByteArray {
        val out = ByteArrayOutputStream()
        doc.save(out)
        return out.toByteArray()
    }

    private fun textPage(doc: PDDocument, text: String) {
        val page = PDPage().also { doc.addPage(it) }
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 14f)
            cs.newLineAtOffset(60f, 700f)
            cs.showText(text)
            cs.endText()
        }
    }

    /** 一页纯图片（模拟扫描件）：整页铺一张带字的位图，没有文字层。 */
    private fun imagePage(doc: PDDocument) {
        val img = BufferedImage(595, 842, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 595, 842)
        g.color = Color.BLACK
        g.font = Font("SansSerif", Font.PLAIN, 28)
        g.drawString("SCANNED PAGE", 80, 200)
        g.dispose()
        val png = File.createTempFile("scan", ".png")
        ImageIO.write(img, "png", png)
        val page = PDPage().also { doc.addPage(it) }
        val image = PDImageXObject.createFromFileByContent(png, doc)
        PDPageContentStream(doc, page).use { cs -> cs.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
        png.delete()
    }

    @Test
    fun plainTextPdfIsRead() {
        val bytes = PDDocument().use { doc -> textPage(doc, "Hello PDF 12345"); save(doc) }
        val out = PdfText.read(bytes)
        assertTrue("应读到英文，实际：[" + out.text + "]", out.text.contains("Hello"))
        assertEquals(1, out.pages)
    }

    @Test
    fun chinesePdfIsRead() {
        assumeTrue("没有中文字体，跳过", chineseFont.exists())
        val bytes = PDDocument().use { doc ->
            val font = PDType0Font.load(doc, chineseFont)
            val page = PDPage().also { doc.addPage(it) }
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(font, 16f); cs.newLineAtOffset(60f, 700f)
                cs.showText("中文测试：这是一段中文内容。"); cs.endText()
            }
            save(doc)
        }
        val out = PdfText.read(bytes)
        assertTrue("应读到中文，实际：[" + out.text + "]", out.text.contains("中文测试"))
    }

    @Test
    fun mixedPdfKeepsTheTextPageWhenAnotherPageHasNoText() {
        val bytes = PDDocument().use { doc ->
            textPage(doc, "TEXT PAGE")
            imagePage(doc)
            save(doc)
        }
        val out = PdfText.read(bytes)
        assertEquals(2, out.pages)
        assertTrue("有文字的那页要读到，实际：[" + out.text + "]", out.text.contains("TEXT PAGE"))
    }

    @Test
    fun scannedPdfSaysItIsAScan() {
        val bytes = PDDocument().use { doc -> imagePage(doc); save(doc) }
        val message = try {
            PdfText.read(bytes)
            fail("扫描件不该被当成读成功")
            ""
        } catch (e: FileException) {
            e.message.orEmpty()
        }
        assertTrue("要明确说是扫描件/图片，实际：[$message]", message.contains("扫描") || message.contains("图片"))
    }

    // ---- 手工构造的边界样本（PDFBox 不会生成这些形状） ----

    private fun handPdf(vararg objects: String): ByteArray =
        ("%PDF-1.4\n" + objects.mapIndexed { i, body -> "${i + 1} 0 obj\n$body\nendobj\n" }.joinToString("") +
            "trailer\n<< /Root 1 0 R /Size ${objects.size + 1} >>\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)

    private fun streamObj(dict: String, content: String): String =
        "$dict\nstream\n$content\nendstream"

    @Test
    fun textInsideAFormXObjectIsRead() {
        // 很多排版软件把整页文字放进 /XObject /Subtype /Form，页面内容只剩一个 Do。
        val font = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        val page = "BT /F1 12 Tf 20 700 Td (page text) Tj ET /Fm1 Do"
        val form = "BT /F2 12 Tf 20 650 Td (form text) Tj ET"
        val bytes = handPdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> /XObject << /Fm1 6 0 R >> >> /Contents 5 0 R >>",
            font,
            streamObj("<< /Length ${page.length} >>", page),
            streamObj(
                "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] /Resources << /Font << /F2 4 0 R >> >> /Length ${form.length} >>",
                form,
            ),
        )
        val text = PdfText.read(bytes).text
        assertTrue(text, text.contains("page text"))
        assertTrue("Form 里的文字也要读到，实际：[$text]", text.contains("form text"))
    }

    @Test
    fun type0WithoutUnicodeMapIsExplainedNotCalledAScan() {
        val content = "BT /F1 12 Tf 20 700 Td <0001> Tj ET"
        val bytes = handPdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type0 /BaseFont /SimSun /Encoding /Identity-H >>",
            streamObj("<< /Length ${content.length} >>", content),
        )
        val message = try { PdfText.read(bytes); fail("没映射的 Type0 不该读成功"); "" } catch (e: FileException) { e.message.orEmpty() }
        assertTrue("要说清是字体缺映射，实际：[$message]", message.contains("Unicode 映射"))
    }

    @Test
    fun aBrokenCompressedStreamIsBlamedOnTheCompression() {
        val bytes = handPdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            "<< /Length 10 /Filter /FlateDecode >>\nstream\nNOT-ZLIB-DATA\nendstream",
        )
        val message = try { PdfText.read(bytes); fail("坏压缩流不该读成功"); "" } catch (e: FileException) { e.message.orEmpty() }
        assertTrue("要说清是压缩方式不支持，实际：[$message]", message.contains("压缩"))
    }
}
