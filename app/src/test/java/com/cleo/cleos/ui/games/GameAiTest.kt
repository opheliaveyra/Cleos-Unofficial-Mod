package com.cleo.cleos.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-B 的解析：模型只说 0..8 的一个数字才算数。 */
class GameAiTest {
    private fun game(vararg cells: Int): TicTacToe {
        var g = TicTacToe()
        for (c in cells) g = g.play(c)
        return g
    }

    @Test
    fun aPlainNumberIsTaken() {
        val g = game(4) // X 在中心，轮到 O
        assertEquals(0, GameAi.parseMove("0", g))
        assertEquals(8, GameAi.parseMove("8", g))
    }

    @Test
    fun numbersAroundTheAnswerAreIgnored() {
        val g = game(4)
        assertEquals(3, GameAi.parseMove("我下 3 吧", g))
        assertEquals(5, GameAi.parseMove("5。", g))
    }

    @Test
    fun anOccupiedCellIsRefused() {
        val g = game(4) // 4 被 X 占
        assertNull(GameAi.parseMove("4", g))
    }

    @Test
    fun outOfRangeIsRefused() {
        val g = game(4)
        assertNull(GameAi.parseMove("9", g))
        assertNull(GameAi.parseMove("99", g))
        assertNull(GameAi.parseMove("hello", g))
    }

    @Test
    fun thePromptNamesTheEmptyCellsAndAsksForOneNumber() {
        val g = game(4)
        val p = GameAi.prompt(g)
        assertTrue(p.contains("X"))
        assertTrue(p.contains("O"))
        assertTrue(p.contains("0"))
        assertTrue(p.contains("8"))
        assertTrue(p.contains("编号"))
    }
}
