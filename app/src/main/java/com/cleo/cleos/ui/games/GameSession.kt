package com.cleo.cleos.ui.games

/**
 * 一局棋的本地状态机（纯 Kotlin，可单测）。
 *
 * 权威规则在这里：只有 [playAsHuman] / [resolveTa] 能改棋盘；请求用 [PendingRequest.requestId]
 * + [revision] 认领。迟到的、重复的、对不上号的回复一律丢弃 —— 旧局面上的旧回复
 * 不能覆盖新棋局（任务书 P0-C 第 1、2 条）。
 */
data class GameSession(
    val id: String,
    val taId: Long,
    val game: TicTacToe = TicTacToe(),
    /** 每次落子 +1；请求带着它走，回来必须对得上。 */
    val revision: Long = 0,
    /** 正在进行的 TA 请求；null = 没有。 */
    val pending: PendingRequest? = null,
) {
    data class PendingRequest(val requestId: String, val revision: Long)

    val canAskTa: Boolean get() = pending == null && !game.over && game.turn == 2

    /** 人落子。不能下（有请求/不是人回合/结束了/格子不对）返回 null。 */
    fun playAsHuman(cell: Int): GameSession? {
        if (pending != null || game.over || game.turn != 1 || !game.canPlay(cell, 1)) return null
        return copy(game = game.play(cell, 1), revision = revision + 1)
    }

    /** 认领一次 TA 请求：已经有在途请求 / 不是 TA 回合 / 结束了 → null（防双击、防重入）。 */
    fun claimTaMove(requestId: String): GameSession? {
        if (!canAskTa) return null
        return copy(pending = PendingRequest(requestId, revision))
    }

    /** TA 的结果回来：requestId 和 revision 都对、且格子能下，才落子。 */
    fun resolveTa(requestId: String, move: Int): GameSession? {
        val p = pending ?: return null
        if (p.requestId != requestId || p.revision != revision) return null
        if (!game.canPlay(move, 2)) return null
        return copy(game = game.play(move, 2), revision = revision + 1, pending = null)
    }

    /** TA 失败（网络/超时/非法）：对得上号的请求才能结束它；棋盘与 revision 都不动。 */
    fun failTa(requestId: String): GameSession? {
        val p = pending ?: return null
        if (p.requestId != requestId) return null
        return copy(pending = null)
    }

    /** 重开 / 换 TA：新 id、清 pending。旧请求的 id 对不上，结果会被 [resolveTa] 丢掉。 */
    fun restart(taId: Long = this.taId): GameSession =
        GameSession(id = java.util.UUID.randomUUID().toString(), taId = taId)

    companion object {
        fun newGame(taId: Long): GameSession =
            GameSession(id = java.util.UUID.randomUUID().toString(), taId = taId)
    }
}
