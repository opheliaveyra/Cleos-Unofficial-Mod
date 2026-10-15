package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Where a file's text is filed. This is a naming rule and nothing else, but getting it wrong
 * once cost the person the copy they sent: a plain .txt filed its own text under the name it
 * already had, and the write landed on top of it.
 */
class FileStoreTest {
    @Test
    fun theTextOfAFileNeverLandsOnTheFileItself() {
        for (name in listOf("a1b2c3.txt", "a1b2c3.md", "a1b2c3.pdf", "a1b2c3.docx", "noextension")) {
            assertNotEquals(name, FileStore.textName(name))
        }
        assertEquals("a1b2c3.txt.text", FileStore.textName("a1b2c3.txt"))
        assertEquals("a1b2c3.pdf.text", FileStore.textName("a1b2c3.pdf"))
    }

    @Test
    fun aFileWhoseNameHoldsDotsKeepsThemAll() {
        assertEquals("会议记录.终稿.pdf.text", FileStore.textName("会议记录.终稿.pdf"))
    }
}
