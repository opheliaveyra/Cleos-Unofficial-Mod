package com.cleo.cleos.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The TA's own folder. What matters most here is the boundary: everything else is file handling,
 * but a path that climbs out of the workspace is the one thing that must never work.
 */
class WorkspaceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun workspace() = Workspace(File(temp.root, "ws"))

    @Test
    fun aPathThatLeavesTheFolderIsRefused() {
        val root = File(temp.root, "ws").apply { mkdirs() }
        for (bad in listOf(
            "../outside.txt",
            "a/../../outside.txt",
            "notes/../../databases/cleos.db",
            "/etc/passwd",
            "/",
            "./notes.md",
            "notes/./today.md",
        )) {
            assertNull(bad, Workspace.resolveIn(root, bad))
        }
    }

    @Test
    fun anOrdinaryPathLandsInsideTheFolder() {
        val root = File(temp.root, "ws").apply { mkdirs() }
        assertEquals(File(root, "notes/today.md"), Workspace.resolveIn(root, "notes/today.md"))
        assertEquals(File(root, "笔记.md"), Workspace.resolveIn(root, " 笔记.md "))
        assertEquals(root, Workspace.resolveIn(root, ""))
    }

    @Test
    fun writingReadingAndAppendingBehaveLikeANotebook() {
        val ws = workspace()
        assertFalse(ws.exists("笔记/想法.md"))
        // Appending to something that is not there yet makes it, folders and all.
        ws.append("笔记/想法.md", "第一条")
        assertTrue(ws.exists("笔记/想法.md"))
        assertEquals("第一条", ws.read("笔记/想法.md", 0, 100))
        ws.append("笔记/想法.md", "\n第二条")
        assertEquals("第一条\n第二条", ws.read("笔记/想法.md", 0, 100))
        assertEquals(1, ws.list("笔记").size)
        // Writing replaces what was there; appending never does.
        ws.write("笔记/想法.md", "换掉了")
        assertEquals("换掉了", ws.read("笔记/想法.md", 0, 100))
    }

    @Test
    fun readingTakesASliceAndSaysWhereItEnded() {
        val ws = workspace()
        val text = "一二三四五六七八九十"
        ws.write("长文.md", text)
        assertEquals("一二三", ws.read("长文.md", 0, 3))
        assertEquals("四五六", ws.read("长文.md", 3, 3))
        assertEquals(10, ws.length("长文.md"))
    }

    @Test
    fun foldersComeFirstAndCarryTheirPath() {
        val ws = workspace()
        ws.write("b.md", "b")
        ws.write("a.md", "a")
        ws.makeDir("目录")
        val names = ws.list().map { it.path }
        assertEquals(listOf("目录", "a.md", "b.md"), names)
        assertTrue(ws.list().first().directory)
    }

    @Test
    fun movingRefusesToLandOnSomethingAlreadyThere() {
        val ws = workspace()
        ws.write("a.md", "a")
        ws.write("b.md", "b")
        ws.move("a.md", "c.md")
        assertTrue(ws.exists("c.md"))
        assertFalse(ws.exists("a.md"))
        assertThrows(WorkspaceException::class.java) { ws.move("c.md", "b.md") }
    }

    @Test
    fun deletingTakesAFolderAndEverythingInIt() {
        val ws = workspace()
        ws.write("草稿/一.md", "一")
        ws.write("草稿/二.md", "二")
        assertEquals(3, ws.delete("草稿"))
        assertFalse(ws.exists("草稿"))
    }

    @Test
    fun theWorkspaceItselfCannotBeDeleted() {
        val ws = workspace()
        ws.write("a.md", "a")
        assertThrows(WorkspaceException::class.java) { ws.delete("") }
        assertTrue(ws.exists("a.md"))
    }

    @Test
    fun aFileBiggerThanTheCapIsRefusedRatherThanWritten() {
        val ws = workspace()
        val tooBig = "x".repeat(Workspace.MAX_FILE + 1)
        assertThrows(WorkspaceException::class.java) { ws.write("大.md", tooBig) }
        val justFits = "x".repeat(Workspace.MAX_FILE)
        ws.write("刚好.md", justFits)
        assertEquals(Workspace.MAX_FILE, ws.length("刚好.md"))
    }

    @Test
    fun describingSaysWhatIsAboutToBeDeleted() {
        val ws = workspace()
        ws.write("草稿/一.md", "一二三")
        assertTrue(ws.describe("草稿").startsWith("文件夹"))
        assertTrue(ws.describe("草稿").contains("1 个文件"))
        assertTrue(ws.describe("草稿/一.md").contains("文件"))
    }

    @Test
    fun readingSomethingThatIsNotThereSaysSoInsteadOfReturningNothing() {
        val ws = workspace()
        val error = assertThrows(WorkspaceException::class.java) { ws.read("没有.md", 0, 10) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("没有"))
        assertThrows(WorkspaceException::class.java) { ws.list("没有这个文件夹") }
    }
}
