package com.cleo.cleos.data

import com.cleo.cleos.data.db.GameSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P0-C 持久化：存档编解码与恢复（在途请求清掉、损坏数据拒绝）。 */
class GameSessionCodecTest {
    private fun row(
        board: String = "1,0,0,0,2,0,0,0,0",
        turn: Int = 2,
        winner: Int = 0,
        moves: Int = 2,
        revision: Long = 2,
        pending: Boolean = true,
    ) = GameSessionEntity(
        id = "g1", taId = 7L, board = board, turn = turn, winner = winner, moves = moves,
        revision = revision, createdAt = 0, updatedAt = 0, pending = pending,
    )

    @Test
    fun aBoardSurvivesTheRoundTrip() {
        val board = listOf(1, 0, 0, 0, 2, 0, 0, 0, 0)
        assertEquals(board, GameSessionCodec.decode(GameSessionCodec.encode(board)))
    }

    @Test
    fun aBrokenBoardIsRefused() {
        assertNull(GameSessionCodec.decode("1,0,0"))
        assertNull(GameSessionCodec.decode("1,0,0,0,2,0,0,0,x"))
        assertNull(GameSessionCodec.decode("1,0,0,0,3,0,0,0,0"))
        assertNull(GameSessionCodec.decode(""))
    }

    @Test
    fun aRestoredGameKeepsBoardAndRevisionButDropsTheRequest() {
        val s = GameSessionCodec.restore(row())!!
        assertEquals(2, s.game.turn)
        assertEquals(2L, s.revision)
        assertNull(s.pending) // 在途请求不会自己回来
        assertEquals(listOf(1, 0, 0, 0, 2, 0, 0, 0, 0), s.game.board)
    }

    @Test
    fun aBrokenSaveIsRefusedNotThrown() {
        assertNull(GameSessionCodec.restore(row(board = "bogus")))
        assertNull(GameSessionCodec.restore(row(turn = 5)))
    }

    @Test
    fun afterRestoreTheTaCanBeAskedAgain() {
        val s = GameSessionCodec.restore(row())!!
        // 恢复后 O 还能被认领（请求已清）
        val claimed = s.claimTaMove("r1")
        assertEquals("r1", claimed?.pending?.requestId)
    }
}
