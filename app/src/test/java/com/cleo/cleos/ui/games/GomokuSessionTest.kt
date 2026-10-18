package com.cleo.cleos.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 五子棋 P1：请求认领、迟到/重复/旧局回复、重开失效。 */
class GomokuSessionTest {
    private fun started(): GomokuSession = GomokuSession.newGame(7L).playAsHuman(7, 7)!!

    @Test
    fun theHumanMoveTurnsItOverToTheTa() {
        val s = started()
        assertEquals(2, s.game.turn)
        assertEquals(1, s.game.at(7, 7))
        assertEquals(1, s.revision)
    }

    @Test
    fun aSecondRequestCannotBeClaimedWhileOneIsPending() {
        val claimed = started().claimTaMove("r1")!!
        assertNull(claimed.claimTaMove("r2"))
        assertEquals("r1", claimed.pending?.requestId)
    }

    @Test
    fun aCorrectReplyIsPlayed() {
        val claimed = started().claimTaMove("r1")!!
        val resolved = claimed.resolveTa("r1", 8, 8)!!
        assertEquals(2, resolved.game.at(8, 8))
        assertEquals(1, resolved.game.turn)
        assertNull(resolved.pending)
        assertEquals(2, resolved.revision)
    }

    @Test
    fun aReplyForAnotherRequestIsDropped() {
        val claimed = started().claimTaMove("r1")!!
        assertNull(claimed.resolveTa("r2", 8, 8))
    }

    @Test
    fun aLateReplyFromAnOlderRevisionIsDropped() {
        val claimed = started().claimTaMove("r1")!!
        val newer = claimed.copy(revision = claimed.revision + 5)
        assertNull(newer.resolveTa("r1", 8, 8))
    }

    @Test
    fun theSameReplyCannotBeAppliedTwice() {
        val claimed = started().claimTaMove("r1")!!
        val once = claimed.resolveTa("r1", 8, 8)!!
        assertNull(once.resolveTa("r1", 8, 9))
    }

    @Test
    fun restartInvalidatesTheOldRequest() {
        val claimed = started().claimTaMove("r1")!!
        val fresh = claimed.restart()
        assertNull(fresh.pending)
        assertTrue(fresh.id != claimed.id)
        assertNull(fresh.resolveTa("r1", 8, 8))
    }

    @Test
    fun failureKeepsTheBoardAndFreesTheRequest() {
        val claimed = started().claimTaMove("r1")!!
        val failed = claimed.failTa("r1")!!
        assertEquals(claimed.game.board, failed.game.board)
        assertEquals(claimed.revision, failed.revision)
        assertNull(failed.pending)
        assertNotNull(failed.claimTaMove("r2"))
    }

    @Test
    fun anIllegalTaReplyIsRefused() {
        val claimed = started().claimTaMove("r1")!!
        assertNull(claimed.resolveTa("r1", 7, 7)) // 已占
        assertNull(claimed.resolveTa("r1", 15, 0)) // 越界
        assertNull(claimed.resolveTa("r1", -1, 0))
        assertNotNull(claimed.resolveTa("r1", 0, 0))
    }

    @Test
    fun noRequestsAfterTheGameIsOver() {
        // 黑 (7,1..5) 横五连；白在 (0,x) 陪跑
        var s = GomokuSession.newGame(7L)
        s = s.playAsHuman(7, 1)!!
        s = s.claimTaMove("a")!!.resolveTa("a", 0, 1)!!
        s = s.playAsHuman(7, 2)!!
        s = s.claimTaMove("b")!!.resolveTa("b", 0, 2)!!
        s = s.playAsHuman(7, 3)!!
        s = s.claimTaMove("c")!!.resolveTa("c", 0, 3)!!
        s = s.playAsHuman(7, 4)!!
        s = s.claimTaMove("d")!!.resolveTa("d", 0, 4)!!
        s = s.playAsHuman(7, 5)!!
        assertEquals(1, s.game.winner)
        assertNull(s.claimTaMove("e"))
        assertNull(s.playAsHuman(7, 6))
    }
}
