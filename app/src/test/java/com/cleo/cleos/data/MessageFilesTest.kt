package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file sent in the chat: how it is written down, how it is described, and how it reads. */
class MessageFilesTest {
    @Test
    fun aListSurvivesBeingWrittenAndReadBack() {
        val files = listOf(
            MessageFile("a1.pdf", "报告.pdf", 2_300_000, MessageFile.PDF, chars = 18_342, pages = 12),
            MessageFile("b2.md", "笔记.md", 3_400, MessageFile.MD, chars = 812),
        )
        assertEquals(files, MessageFiles.decode(MessageFiles.encode(files)))
    }

    @Test
    fun aFileThatCouldNotBeReadKeepsItsReason() {
        val failed = MessageFile("c3.pdf", "扫描件.pdf", 900_000, MessageFile.PDF, error = "这个 PDF 里没有文字")
        val back = MessageFiles.decode(MessageFiles.encode(listOf(failed))).single()
        assertEquals("这个 PDF 里没有文字", back.error)
        assertFalse(back.ready)
    }

    @Test
    fun nothingSentReadsAsNothingRatherThanFailing() {
        assertNull(MessageFiles.encode(emptyList()))
        assertTrue(MessageFiles.decode(null).isEmpty())
        assertTrue(MessageFiles.decode("").isEmpty())
        assertTrue(MessageFiles.decode("这不是 JSON").isEmpty())
    }

    @Test
    fun anUnknownFieldFromALaterVersionDoesNotLoseTheFile() {
        val raw = """[{"file":"a1.txt","name":"a.txt","size":12,"kind":"txt","chars":5,"extra":true}]"""
        assertEquals("a.txt", MessageFiles.decode(raw).single().name)
    }

    @Test
    fun sizesReadTheWayAPersonWouldSayThem() {
        assertEquals("97 B", readableSize(97))
        assertEquals("1.0 KB", readableSize(1024))
        assertEquals("812.0 KB", readableSize(831_488))
        assertEquals("2.2 MB", readableSize(2_300_000))
    }

    @Test
    fun theFourKindsAreToldApartByName() {
        assertEquals(MessageFile.TXT, fileKind("notes.txt", null))
        assertEquals(MessageFile.MD, fileKind("readme.MD", null))
        assertEquals(MessageFile.PDF, fileKind("报告.pdf", null))
        assertEquals(MessageFile.DOCX, fileKind("会议.docx", null))
        // A name that says nothing falls back to what the picker reported.
        assertEquals(MessageFile.PDF, fileKind("download", "application/pdf"))
        assertEquals(MessageFile.TXT, fileKind("mystery", "text/plain; charset=utf-8"))
        assertNull(fileKind("photo.jpg", "image/jpeg"))
        assertNull(fileKind("archive.zip", "application/zip"))
    }

    @Test
    fun aKindUnlikeTheNameWinsOnlyWhenTheNameSaysNothing() {
        // The extension is what the person sees; a phone that reports text/plain for everything
        // must not turn a PDF into a text file.
        assertEquals(MessageFile.PDF, fileKind("report.pdf", "text/plain"))
    }
}
