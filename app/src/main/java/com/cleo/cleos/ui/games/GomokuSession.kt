package com.cleo.cleos.ui.games

/**
 * 五子棋一局的本地状态机（仿 [GameSession]，纯 Kotlin 可测）。
 *
 * 请求用 requestId + revision 认领；迟到的、重复的、旧局的回复一律丢弃。
 * 规则本身在 [Gomoku] 里，这里只负责"谁在落子、这步请求归谁"。
 */
data class GomokuSession(
    val id: String,
    val taId: Long,
    val game: Gomoku = Gomoku(),
    val revision: Long = 0,
    val pending: PendingRequest? = null,
) {
    data class PendingRequest(val requestId: String, val revision: Long)

    val canAskTa: Boolean get() = pending == null && !game.over && game.turn == 2

    fun playAsHuman(row: Int, col: Int): GomokuSession? {
        if (pending != null || game.over || game.turn != 1 || !game.canPlay(row, col, 1)) return null
        return copy(game = game.play(row, col, 1), revision = revision + 1)
    }

    fun claimTaMove(requestId: String): GomokuSession? {
        if (!canAskTa) return null
        return copy(pending = PendingRequest(requestId, revision))
    }

    fun resolveTa(requestId: String, row: Int, col: Int): GomokuSession? {
        val p = pending ?: return null
        if (p.requestId != requestId || p.revision != revision) return null
        if (!game.canPlay(row, col, 2)) return null
        return copy(game = game.play(row, col, 2), revision = revision + 1, pending = null)
    }

    fun failTa(requestId: String): GomokuSession? {
        val p = pending ?: return null
        if (p.requestId != requestId) return null
        return copy(pending = null)
    }

    fun restart(taId: Long = this.taId): GomokuSession =
        GomokuSession(id = java.util.UUID.randomUUID().toString(), taId = taId)

    companion object {
        fun newGame(taId: Long): GomokuSession =
            GomokuSession(id = java.util.UUID.randomUUID().toString(), taId = taId)
    }
}
