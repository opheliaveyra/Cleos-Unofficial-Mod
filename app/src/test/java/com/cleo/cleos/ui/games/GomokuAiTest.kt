package com.cleo.cleos.ui.games

import com.cleo.cleos.data.db.CompanionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-B：严格坐标解析与 prompt 形状。 */
class GomokuAiTest {
    private fun ta(name: String = "肖恩", persona: String = "安静") =
        CompanionEntity(id = 1L, name = name, persona = persona, apiBaseUrl = "https://example.com/v1", apiModel = "m", createdAt = 0L)

    private fun started(): Gomoku = Gomoku().play(7, 7, 1) // 轮到白

    @Test
    fun aPlainCoordinateIsTaken() {
        val g = started()
        assertEquals(7 to 8, GomokuAi.parseMove("7,8", g))
        assertEquals(0 to 0, GomokuAi.parseMove(" 0,0 ", g))
    }

    @Test
    fun nonStrictAnswersAreRefused() {
        val g = started()
        assertNull(GomokuAi.parseMove("7", g))
        assertNull(GomokuAi.parseMove("7,", g))
        assertNull(GomokuAi.parseMove("7,8。", g))
        assertNull(GomokuAi.parseMove("我下 7,8", g))
        assertNull(GomokuAi.parseMove("7,8 或者 8,8", g))
        assertNull(GomokuAi.parseMove("-1,0", g))
        assertNull(GomokuAi.parseMove("", g))
    }

    @Test
    fun outOfRangeAndOccupiedAreRefused() {
        val g = started()
        assertNull(GomokuAi.parseMove("15,0", g))
        assertNull(GomokuAi.parseMove("0,15", g))
        assertNull(GomokuAi.parseMove("7,7", g)) // 已被黑占
    }

    @Test
    fun thePromptHasTheBoardAndTheCoordinateRule() {
        val p = GomokuAi.prompt(started(), ta())
        assertTrue(p.contains("肖恩"))
        assertTrue(p.contains("X"))
        assertTrue(p.contains("O"))
        assertTrue(p.contains("0-14"))
        assertTrue(p.contains("行,列"))
        // 15 行棋盘
        assertEquals(15, p.lines().count { l -> l.length == 15 && l.all { it == '.' || it == 'X' || it == 'O' } })
    }
}
