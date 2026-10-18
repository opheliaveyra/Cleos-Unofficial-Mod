package com.cleo.cleos.games

import org.junit.Assert.*
import org.junit.Test

class TicTacToeTest {
    private fun fresh() = GameSession("game-a", 1, playerTaCreatedAt = 42)
    private fun play(vararg cells: Int): GameSession = cells.fold(fresh()) { s, cell -> s.move(cell, s.turn) }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun everyWinningLineAndDraw() {
        val lines = listOf(listOf(0,1,2), listOf(3,4,5), listOf(6,7,8), listOf(0,3,6),
            listOf(1,4,7), listOf(2,5,8), listOf(0,4,8), listOf(2,4,6))
        for (mark in listOf("X", "O")) for (line in lines) {
            val board = MutableList(9) { "" }
            line.forEach { board[it] = mark }
            assertEquals(mark, TicTacToe.result(board))
        }
        assertEquals("draw", play(0,1,2,4,3,5,7,6,8).result)
    }
    @Test fun invalidMovesAndFinishedGamesDoNotAdvance() {
        val s = fresh()
        rejects { s.move(0, "O") }
        rejects { s.move(-1, "X") }
        rejects { s.move(9, "X") }
        val played = s.move(0, "X")
        rejects { played.move(0, "O") }
        rejects { played.move(1, "X") }
        val win = play(0,3,1,4,2)
        assertEquals("X", win.result)
        assertTrue(win.legal.isEmpty())
        rejects { win.move(8, "O") }
        assertEquals(emptyList<Int>(), s.history)
    }
    @Test fun modelActionsAreStrictAndOnlyLegal() {
        val s = play(4)
        assertEquals(0, TicTacToe.action(" 0\n", s))
        listOf("4", "9", "-1", "00", "1 2", "```0```", "{\"cell\":0}", "I choose 0", "拒绝", "").forEach {
            rejects { TicTacToe.action(it, s) }
        }
    }
    @Test fun lateAndDuplicateResponsesAreRejected() {
        val pending = play(0)
        assertTrue(pending.accepts("game-a", 1))
        assertFalse(pending.accepts("other-game", 1))
        assertFalse(pending.accepts("game-a", 0))
        val replied = pending.move(4, "O")
        assertFalse(replied.accepts("game-a", 1))
        val nextTurn = replied.move(1, "X")
        assertFalse(nextTurn.accepts("game-a", 1))
        assertFalse(fresh().accepts("game-a", 0))
    }
    @Test fun serializationRoundTripKeepsTurnAndIdentity() {
        val s = play(0,4,1)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val restored = TicTacToe.validate(json.decodeFromString<GameSession>(json.encodeToString(s)))
        assertEquals(s, restored)
        assertEquals("O", restored.turn)
        assertEquals(42L, restored.playerTaCreatedAt)
    }
    @Test fun invalidSavedHistoriesAreRejected() {
        val s = play(0,4,1)
        assertEquals(s, TicTacToe.validate(s))
        rejects { TicTacToe.validate(s.copy(revision = 100)) }
        rejects { TicTacToe.validate(s.copy(history = listOf(0,0))) }
        rejects { TicTacToe.validate(s.copy(history = listOf(9))) }
        rejects { TicTacToe.validate(s.copy(history = listOf(0,3,1,4,2,8), revision = 6)) }
    }
    @Test fun enumerateEveryLegalGame() {
        var xWins = 0; var oWins = 0; var draws = 0
        fun visit(s: GameSession) {
            when (s.result) {
                "X" -> { xWins++; return }
                "O" -> { oWins++; return }
                "draw" -> { draws++; return }
            }
            val cells = s.board
            assertEquals(s.history.size, cells.count { it.isNotEmpty() })
            assertEquals(s.history.size, s.revision)
            for (cell in s.legal) visit(s.move(cell, s.turn))
        }
        visit(fresh())
        assertEquals(131184, xWins)
        assertEquals(77904, oWins)
        assertEquals(46080, draws)
    }
}
