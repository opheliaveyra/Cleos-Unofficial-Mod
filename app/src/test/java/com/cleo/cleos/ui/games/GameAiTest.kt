package com.cleo.cleos.ui.games

import com.cleo.cleos.data.db.CompanionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-C：严格解析（不再从整段文字里扫数字）与 prompt 形状。 */
class GameAiTest {
    private fun game(vararg cells: Int): TicTacToe {
        var g = TicTacToe()
        for (c in cells) g = g.play(c)
        return g
    }

    private fun ta(name: String = "肖恩", persona: String = "温柔、话少") =
        CompanionEntity(id = 1L, name = name, persona = persona, apiBaseUrl = "https://example.com/v1", apiModel = "m", createdAt = 0L)

    @Test
    fun aSingleNumberIsTaken() {
        val g = game(4) // X 在中心，轮到 O
        assertEquals(0, GameAi.parseMove("0", g))
        assertEquals(8, GameAi.parseMove(" 8 ", g))
    }

    @Test
    fun explanationsAndMultipleNumbersAreRefused() {
        val g = game(4)
        assertNull(GameAi.parseMove("我下 3 吧", g))
        assertNull(GameAi.parseMove("考虑 0 或 2", g))
        assertNull(GameAi.parseMove("0 2", g))
        assertNull(GameAi.parseMove("3。", g))
        assertNull(GameAi.parseMove("", g))
        assertNull(GameAi.parseMove("   ", g))
    }

    @Test
    fun negativesAndOutOfRangeAreRefused() {
        val g = game(4)
        assertNull(GameAi.parseMove("-1", g))
        assertNull(GameAi.parseMove("9", g))
        assertNull(GameAi.parseMove("99", g))
        assertNull(GameAi.parseMove("hello", g))
    }

    @Test
    fun anOccupiedCellIsRefused() {
        val g = game(4)
        assertNull(GameAi.parseMove("4", g))
    }

    @Test
    fun thePromptCarriesTheTaIdentityAndTheEmptyCells() {
        val g = game(4)
        val p = GameAi.prompt(g, ta())
        assertTrue(p.contains("肖恩"))
        assertTrue(p.contains("温柔"))
        assertTrue(p.contains("X"))
        assertTrue(p.contains("O"))
        assertTrue(p.contains("0 1 2")) // 编号图
        assertTrue(p.contains("只能从这些数字里选"))
    }
}
