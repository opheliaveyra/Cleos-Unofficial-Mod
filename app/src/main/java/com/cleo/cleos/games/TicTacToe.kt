package com.cleo.cleos.games

import kotlinx.serialization.Serializable

/** The board is derived from ordered moves; models never supply state or results. */
@Serializable
data class GameSession(
    val sessionId: String,
    val playerTaId: Long,
    val history: List<Int> = emptyList(),
    val revision: Int = 0,
    val playerTaCreatedAt: Long,
) {
    val board: List<String> get() {
        val cells = MutableList(9) { "" }
        history.forEachIndexed { i, cell -> cells[cell] = if (i % 2 == 0) "X" else "O" }
        return cells
    }
    val result: String? get() = TicTacToe.result(board)
    val turn: String get() = if (history.size % 2 == 0) "X" else "O"
    val legal: List<Int> get() = if (result != null) emptyList() else board.indices.filter { board[it].isEmpty() }
    fun move(cell: Int, mark: String): GameSession {
        require(mark == turn && cell in legal) { "不是当前回合或这个格子不能落子" }
        return copy(history = history + cell, revision = revision + 1)
    }
    fun accepts(id: String, expectedRevision: Int): Boolean =
        sessionId == id && revision == expectedRevision && turn == "O" && result == null
}

object TicTacToe {
    private val lines = listOf(listOf(0,1,2), listOf(3,4,5), listOf(6,7,8),
        listOf(0,3,6), listOf(1,4,7), listOf(2,5,8), listOf(0,4,8), listOf(2,4,6))
    fun result(board: List<String>): String? {
        require(board.size == 9)
        for (line in lines) {
            val mark = board[line[0]]
            if (mark.isNotEmpty() && line.all { board[it] == mark }) return mark
        }
        return if (board.none { it.isEmpty() }) "draw" else null
    }
    fun action(raw: String, session: GameSession): Int {
        val text = raw.trim()
        require(Regex("[0-8]").matches(text)) { "TA 没有返回 0–8 的单个格子编号" }
        return text.toInt().also { require(it in session.legal) { "TA 选择了已经占用的格子" } }
    }
    fun validate(session: GameSession): GameSession {
        require(session.playerTaId > 0 && session.sessionId.isNotBlank())
        var replay = session.copy(history = emptyList(), revision = 0)
        session.history.forEach { replay = replay.move(it, replay.turn) }
        require(replay == session) { "棋局记录损坏" }
        return session
    }
}
