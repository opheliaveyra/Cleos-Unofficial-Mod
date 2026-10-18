package com.cleo.cleos.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-C：请求认领、迟到/重复/旧局面回复、重开失效。纯逻辑，不需要网络。 */
class GameSessionTest {
    private fun started(): GameSession {
        var s = GameSession.newGame(taId = 7L)
        val afterHuman = s.playAsHuman(4)!!
        return afterHuman
    }

    @Test
    fun theHumanMoveTurnsItOverToTheTa() {
        val s = started()
        assertEquals(2, s.game.turn)
        assertEquals(1, s.game.board[4])
        assertEquals(1, s.revision)
    }

    @Test
    fun aSecondRequestCannotBeClaimedWhileOneIsPending() {
        val claimed = started().claimTaMove("r1")!!
        assertNull(claimed.claimTaMove("r2")) // 双击/重入：第二个请求认领失败
        assertEquals("r1", claimed.pending?.requestId)
    }

    @Test
    fun aCorrectReplyPlaysTheTaMove() {
        val claimed = started().claimTaMove("r1")!!
        val resolved = claimed.resolveTa("r1", 0)!!
        assertEquals(2, resolved.game.board[0])
        assertEquals(1, resolved.game.turn) // 又轮到手
        assertNull(resolved.pending)
        assertEquals(2, resolved.revision)
    }

    @Test
    fun aReplyForAnotherRequestIsDropped() {
        val claimed = started().claimTaMove("r1")!!
        assertNull(claimed.resolveTa("r2", 0))
    }

    @Test
    fun aLateReplyFromAnOlderRevisionIsDropped() {
        val claimed = started().claimTaMove("r1")!!
        // 局面已经变了（比如重开后又走了一步），旧 revision 的回复不能落子
        val newer = claimed.copy(revision = claimed.revision + 5)
        assertNull(newer.resolveTa("r1", 0))
    }

    @Test
    fun theSameReplyCannotBeAppliedTwice() {
        val claimed = started().claimTaMove("r1")!!
        val once = claimed.resolveTa("r1", 0)!!
        assertNull(once.resolveTa("r1", 8)) // pending 已清，重复回复无效
    }

    @Test
    fun restartInvalidatesTheOldRequest() {
        val claimed = started().claimTaMove("r1")!!
        val fresh = claimed.restart()
        assertNull(fresh.pending)
        assertTrue(fresh.id != claimed.id)
        // 旧 session 的回复落到新 session 上必然对不上 id/pending
        assertNull(fresh.resolveTa("r1", 0))
    }

    @Test
    fun failureKeepsTheBoardAndFreesTheRequest() {
        val claimed = started().claimTaMove("r1")!!
        val failed = claimed.failTa("r1")!!
        assertEquals(claimed.game.board, failed.game.board)
        assertEquals(claimed.revision, failed.revision)
        assertNull(failed.pending)
        // 失败之后可以手动继续
        assertNotNull(failed.claimTaMove("r2"))
    }

    @Test
    fun aMoveFromTheWrongTurnIsRefused() {
        val s = GameSession.newGame(7L) // 轮到人（X），此时不该认领 TA 请求
        assertNull(s.claimTaMove("r1"))
        assertNull(s.playAsHuman(9)) // 越界
        assertNotNull(s.playAsHuman(0))
    }

    @Test
    fun noRequestsAfterTheGameIsOver() {
        // X: 0,1,2；O: 3,4
        var s = GameSession.newGame(7L)
        s = s.playAsHuman(0)!!
        s = s.claimTaMove("a")!!.resolveTa("a", 3)!!
        s = s.playAsHuman(1)!!
        s = s.claimTaMove("b")!!.resolveTa("b", 4)!!
        s = s.playAsHuman(2)!!
        assertEquals(1, s.game.winner)
        assertNull(s.claimTaMove("c"))
        assertNull(s.playAsHuman(8))
    }
}
