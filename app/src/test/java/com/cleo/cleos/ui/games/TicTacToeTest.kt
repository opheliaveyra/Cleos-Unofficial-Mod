package com.cleo.cleos.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 井字棋规则与状态机：纯逻辑，不需要设备。 */
class TicTacToeTest {
    private fun playAll(vararg cells: Int): TicTacToe {
        var g = TicTacToe()
        for (c in cells) g = g.play(c)
        return g
    }

    @Test
    fun peoplePlayFirstAndTurnsAlternate() {
        val g = playAll(0, 1)
        assertEquals(1, g.board[0])
        assertEquals(2, g.board[1])
        assertEquals(1, g.turn) // 两步之后又轮到手
    }

    @Test
    fun theSameCellCannotBePlayedTwice() {
        val g = TicTacToe().play(4)
        assertFalse(g.canPlay(4))
    }

    @Test
    fun aRowWins() {
        // X: 0,1,2；O: 3,4
        val g = playAll(0, 3, 1, 4, 2)
        assertEquals(1, g.winner)
        assertTrue(g.over)
        assertNull(g.suggestedMove())
    }

    @Test
    fun aDiagonalWins() {
        val g = playAll(0, 1, 4, 2, 8)
        assertEquals(1, g.winner)
    }

    @Test
    fun aFullBoardWithNoLineIsADraw() {
        // X: 0,2,3,7,8  O: 1,4,5,6  （X 0-2-3-7-8；无三连）
        val g = playAll(0, 1, 2, 4, 3, 5, 7, 6, 8)
        assertEquals(3, g.winner)
        assertTrue(g.over)
    }

    @Test
    fun noMovesAfterTheGameIsOver() {
        val g = playAll(0, 3, 1, 4, 2)
        assertFalse(g.canPlay(5))
        assertFalse(g.canPlay(8))
    }

    @Test
    fun winningIsPreferredOverBlocking() {
        // O 在 6、7（空 8 就赢）；X 在 0、1（空 2 就赢）。轮到 X 之前问 O：应该先下 8 赢。
        var g = TicTacToe()
        g = g.play(0) // X
        g = g.play(6) // O
        g = g.play(1) // X
        g = g.play(7) // O
        val move = g.suggestedMove(forWho = 2)
        assertEquals(8, move)
    }

    @Test
    fun blockingIsPreferredWhenCannotWin() {
        var g = TicTacToe()
        g = g.play(0) // X
        g = g.play(4) // O
        g = g.play(1) // X
        g = g.play(8) // O
        // X 有 0、1，空 2 就赢；O 自己 4、8 没机会（4-8 需 0 但被占）
        val move = g.suggestedMove(forWho = 2)
        assertEquals(2, move)
    }

    @Test
    fun resetClearsEverything() {
        val g = playAll(0, 3, 1).reset()
        assertEquals(0, g.moves)
        assertEquals(1, g.turn)
        assertEquals(0, g.winner)
        assertTrue(g.board.all { it == 0 })
    }
}
