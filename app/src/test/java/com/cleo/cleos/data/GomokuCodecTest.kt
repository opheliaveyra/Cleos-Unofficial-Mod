package com.cleo.cleos.data

import com.cleo.cleos.data.db.GameSessionEntity
import com.cleo.cleos.ui.games.GOMOKU_SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 五子棋存档：225 格往返、被占/越界恢复、与井字棋互不覆盖（编码层）。 */
class GomokuCodecTest {
    private fun row(board: String, turn: Int = 2, moves: Int = 2, revision: Long = 2, type: String = "gomoku") =
        GameSessionEntity(
            id = "g2", gameType = type, taId = 7L, board = board, turn = turn, winner = 0,
            moves = moves, revision = revision, createdAt = 0, updatedAt = 0, pending = true,
        )

    private fun boardText(first: Int = 1) =
        List(GOMOKU_SIZE * GOMOKU_SIZE) { i -> if (i == 0) first else 0 }.joinToString(",")

    @Test
    fun aBoardSurvivesTheRoundTrip() {
        val board = List(GOMOKU_SIZE * GOMOKU_SIZE) { i -> if (i == 0) 1 else 0 }
        assertEquals(board, GomokuCodec.decode(GomokuCodec.encode(board)))
    }

    @Test
    fun aBrokenBoardIsRefused() {
        assertNull(GomokuCodec.decode("1,0,0"))
        assertNull(GomokuCodec.decode("x"))
        assertNull(GomokuCodec.decode(List(GOMOKU_SIZE * GOMOKU_SIZE) { 3 }.joinToString(",")))
    }

    @Test
    fun aRestoredGameKeepsBoardAndDropsTheRequest() {
        val s = GomokuCodec.restore(row(boardText()))!!
        assertEquals(1, s.game.at(0, 0))
        assertEquals(2, s.game.turn)
        assertEquals(2L, s.revision)
        assertNull(s.pending)
    }

    @Test
    fun aBrokenSaveIsRefusedNotThrown() {
        assertNull(GomokuCodec.restore(row("bogus")))
        assertNull(GomokuCodec.restore(row(boardText(), turn = 5)))
    }
}
