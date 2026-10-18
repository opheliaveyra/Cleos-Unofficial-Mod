package com.cleo.cleos.data

import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.GameSessionEntity
import com.cleo.cleos.ui.games.GameSession
import com.cleo.cleos.ui.games.TicTacToe
import kotlinx.coroutines.flow.Flow

/**
 * 棋局的存与取：把 [GameSession] 落成一行，重启后能接着下。
 *
 * 只留最近一局（[save] 会清掉更早的）；保存时把「有没有请求在途」也记下，
 * 进程被杀后恢复时那笔请求不会自己回来，标成「已中断」，不自动重试付费。
 */
class GameSessions(private val db: AppDatabase) {
    fun observeLatest(): Flow<GameSessionEntity?> = db.gameSessions().observeLatest()

    suspend fun latest(): GameSessionEntity? = db.gameSessions().latest()

    suspend fun restore(): Restored? {
        val row = db.gameSessions().latest() ?: return null
        return Restored(row, GameSessionCodec.restore(row))
    }

    /** 保存（顺手清掉旧局 —— 只留最近一局，避免越攒越多）。 */
    suspend fun save(session: GameSession) {
        val now = System.currentTimeMillis()
        db.gameSessions().save(
            GameSessionEntity(
                id = session.id,
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
        db.gameSessions().keepOnly(session.id)
    }

    suspend fun clear() = db.gameSessions().clear()

    /** 恢复结果：原始行（判断 TA 是否还在、是否被打断）+ 会话（损坏时是 null）。 */
    data class Restored(val row: GameSessionEntity, val session: GameSession?)
}

/** 存档与内存会话之间的编解码 —— 纯函数，能单测。 */
internal object GameSessionCodec {
    fun encode(board: List<Int>): String = board.joinToString(",")

    fun decode(text: String): List<Int>? {
        val parts = text.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != 9 || parts.any { it !in 0..2 }) return null
        return parts
    }

    /** 行 → 会话：**在途请求清掉**（进程重启后回不来），其余原样恢复；存档坏了返回 null。 */
    fun restore(row: GameSessionEntity): GameSession? {
        val board = decode(row.board) ?: return null
        if (row.turn !in 1..2 || row.winner !in 0..3 || row.moves !in 0..9) return null
        val game = TicTacToe(board = board, turn = row.turn, winner = row.winner, moves = row.moves)
        return GameSession(id = row.id, taId = row.taId, game = game, revision = row.revision)
    }
}
