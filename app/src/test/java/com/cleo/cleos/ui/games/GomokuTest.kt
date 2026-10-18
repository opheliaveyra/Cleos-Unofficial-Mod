package com.cleo.cleos.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 五子棋 P1-A：横竖双斜五连、边界、非法落子、满盘平局。 */
class GomokuTest {
    private fun play(g: Gomoku, vararg moves: Pair<Int, Int>): Gomoku {
        var cur = g
        for ((i, rc) in moves.withIndex()) {
            val who = if (i % 2 == 0) 1 else 2
            cur = cur.play(rc.first, rc.second, who)
        }
        return cur
    }

    @Test
    fun blackStartsAndTurnsAlternate() {
        var g = Gomoku()
        assertEquals(1, g.turn)
        g = g.play(7, 7, 1)
        assertEquals(2, g.turn)
        assertEquals(1, g.at(7, 7))
        g = g.play(7, 8, 2)
        assertEquals(1, g.turn)
        assertEquals(2, g.at(7, 8))
        assertEquals(2, g.moves)
    }

    @Test
    fun fiveInARowWins() {
        var g = Gomoku()
        g = play(g, 7 to 1, 6 to 1, 7 to 2, 6 to 2, 7 to 3, 6 to 3, 7 to 4, 6 to 4, 7 to 5)
        assertEquals(1, g.winner)
        assertTrue(g.over)
    }

    @Test
    fun verticalFiveWins() {
        var g = Gomoku()
        g = play(g, 3 to 7, 3 to 0, 4 to 7, 4 to 0, 5 to 7, 5 to 0, 6 to 7, 6 to 0, 7 to 7)
        assertEquals(1, g.winner)
    }

    @Test
    fun diagonalFiveWins() {
        var g = Gomoku()
        g = play(g, 5 to 5, 0 to 0, 6 to 6, 0 to 1, 7 to 7, 0 to 2, 8 to 8, 0 to 3, 9 to 9)
        assertEquals(1, g.winner)
    }

    @Test
    fun antiDiagonalFiveWins() {
        var g = Gomoku()
        g = play(g, 5 to 9, 0 to 0, 6 to 8, 0 to 1, 7 to 7, 0 to 2, 8 to 6, 0 to 3, 9 to 5)
        assertEquals(1, g.winner)
    }

    @Test
    fun whiteCanWinToo() {
        var g = Gomoku()
        val black = listOf(0 to 0, 2 to 0, 4 to 0, 6 to 0, 8 to 0)
        val white = listOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5)
        for (i in white.indices) {
            g = g.play(black[i].first, black[i].second, 1)
            g = g.play(white[i].first, white[i].second, 2)
        }
        assertEquals(2, g.winner)
    }

    @Test
    fun fourInARowIsNotAWin() {
        var g = Gomoku()
        g = play(g, 7 to 1, 6 to 1, 7 to 2, 6 to 2, 7 to 3, 6 to 3, 7 to 4, 6 to 4)
        assertFalse(g.over)
        assertEquals(0, g.winner)
    }

    @Test
    fun anOccupiedCellAndTheWrongTurnAreRefused() {
        val g = Gomoku().play(7, 7, 1)
        assertFalse(g.canPlay(7, 7, 2))
        assertFalse(g.canPlay(7, 8, 1))
        assertTrue(g.canPlay(7, 8, 2))
    }

    @Test
    fun outOfBoundsIsRefused() {
        val g = Gomoku()
        assertFalse(g.canPlay(-1, 0, 1))
        assertFalse(g.canPlay(15, 0, 1))
        assertFalse(g.canPlay(0, 15, 1))
    }

    @Test
    fun cornerAndEdgeMovesAreLegal() {
        val g = Gomoku().play(0, 0, 1).play(0, 14, 2).play(14, 0, 1).play(14, 14, 2)
        assertEquals(1, g.at(0, 0))
        assertEquals(2, g.at(0, 14))
        assertEquals(1, g.at(14, 0))
        assertEquals(2, g.at(14, 14))
        assertEquals(4, g.moves)
    }

    @Test
    fun noMovesAfterTheGameIsOver() {
        var g = Gomoku()
        g = play(g, 7 to 1, 6 to 1, 7 to 2, 6 to 2, 7 to 3, 6 to 3, 7 to 4, 6 to 4, 7 to 5)
        assertTrue(g.over)
        assertFalse(g.canPlay(0, 0, 2))
        assertEquals(225 - 9, g.emptyCells().size)
    }

    @Test
    fun aFullBoardIsADraw() {
        // (row + 2*col) % 4 的条纹：它沿横、竖、两条对角线都以 4 为周期变色，任何方向都不会连五
        val filled = MutableList(225) { i ->
            val row = i / 15
            val col = i % 15
            if (row == 0 && col == 0) 0 else if ((row + 2 * col) % 4 < 2) 1 else 2
        }
        val g = Gomoku(board = filled, turn = 1, winner = 0, moves = 224)
        val done = g.play(0, 0, 1)
        assertEquals(3, done.winner)
        assertTrue(done.over)
        assertEquals(225, done.moves)
        assertTrue(done.emptyCells().isEmpty())
    }
}
