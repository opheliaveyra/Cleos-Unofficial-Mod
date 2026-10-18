package com.cleo.cleos.data

import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.GameSessionEntity
import com.cleo.cleos.ui.games.GOMOKU_SIZE
import com.cleo.cleos.ui.games.GameSession
import com.cleo.cleos.ui.games.Gomoku
import com.cleo.cleos.ui.games.GomokuSession
import com.cleo.cleos.ui.games.TicTacToe
import kotlinx.coroutines.flow.Flow

/**
 * 棋局的存与取（井字棋、五子棋）。
 *
 * 每种游戏**各自保留最近一局**，互不覆盖；保存时把「有没有请求在途」也记下，
 * 进程被杀后恢复时那笔请求不会自己回来，标成「已中断」，不自动重试付费。
 */
class GameSessions(private val db: AppDatabase) {
    companion object {
        const val TYPE_TIC_TAC_TOE = "tic_tac_toe"
        const val TYPE_GOMOKU = "gomoku"
    }

    fun observeLatest(gameType: String): Flow<GameSessionEntity?> = db.gameSessions().observeLatest(gameType)

    // ---- 井字棋 ----

    suspend fun restore(): Restored? {
        val row = db.gameSessions().latest(TYPE_TIC_TAC_TOE) ?: return null
        return Restored(row, GameSessionCodec.restore(row))
    }

    suspend fun save(session: GameSession) {
        val now = System.currentTimeMillis()
        db.gameSessions().save(
            GameSessionEntity(
                id = session.id,
                gameType = TYPE_TIC_TAC_TOE,
                taId = session.taId,
                board = GameSessionCodec.encode(session.game.board),
                turn = session.game.turn,
                winner = session.game.winner,
                moves = session.game.moves,
                revision = session.revision,
                createdAt = now,
                updatedAt = now,
                pending = session.pending != null,
            ),
        )
        db.gameSessions().keepOnly(TYPE_TIC_TAC_TOE, session.id)
    }

    // ---- 五子棋 ----

    suspend fun restoreGomoku(): RestoredGomoku? {
        val row = db.gameSessions().latest(TYPE_GOMOKU) ?: return null
        return RestoredGomoku(row, GomokuCodec.restore(row))
    }

    suspend fun saveGomoku(session: GomokuSession) {
        val now = System.currentTimeMillis()
        db.gameSessions().save(
            GameSessionEntity(
                id = session.id,
                gameType = TYPE_GOMOKU,
                taId = session.taId,
                board = GomokuCodec.encode(session.game.board),
                turn = session.game.turn,
                winner = session.game.winner,
                moves = session.game.moves,
                revision = session.revision,
                createdAt = now,
                updatedAt = now,
                pending = session.pending != null,
            ),
        )
        db.gameSessions().keepOnly(TYPE_GOMOKU, session.id)
    }

    suspend fun clear(gameType: String) = db.gameSessions().clear(gameType)

    data class Restored(val row: GameSessionEntity, val session: GameSession?)
    data class RestoredGomoku(val row: GameSessionEntity, val session: GomokuSession?)
}

/** 存档与内存会话之间的编解码 —— 纯函数，能单测。 */
internal object GameSessionCodec {
    fun encode(board: List<Int>): String = board.joinToString(",")

    fun decode(text: String, size: Int): List<Int>? {
        val parts = text.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != size || parts.any { it !in 0..2 }) return null
        return parts
    }

    fun restore(row: GameSessionEntity): GameSession? {
        val board = decode(row.board, 9) ?: return null
        if (row.turn !in 1..2 || row.winner !in 0..3 || row.moves !in 0..9) return null
        val game = TicTacToe(board = board, turn = row.turn, winner = row.winner, moves = row.moves)
        return GameSession(id = row.id, taId = row.taId, game = game, revision = row.revision)
    }
}

/** 五子棋的编解码：225 个 0/1/2。 */
internal object GomokuCodec {
    fun encode(board: List<Int>): String = board.joinToString(",")

    fun decode(text: String): List<Int>? {
        val parts = text.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != GOMOKU_SIZE * GOMOKU_SIZE || parts.any { it !in 0..2 }) return null
        return parts
    }

    fun restore(row: GameSessionEntity): GomokuSession? {
        val board = decode(row.board) ?: return null
        if (row.turn !in 1..2 || row.winner !in 0..3 || row.moves !in 0..(GOMOKU_SIZE * GOMOKU_SIZE)) return null
        val game = Gomoku(board = board, turn = row.turn, winner = row.winner, moves = row.moves)
        return GomokuSession(id = row.id, taId = row.taId, game = game, revision = row.revision)
    }
}
