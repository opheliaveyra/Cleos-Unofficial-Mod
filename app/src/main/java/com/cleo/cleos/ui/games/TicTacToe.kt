package com.cleo.cleos.ui.games

/**
 * 井字棋的**本地权威状态**：棋盘、轮到谁、有没有结束。
 *
 * 任务书里的边界：AI 只决定"下哪儿"（0..8），不碰这里；胜负、回合、合法性全在这类里。
 * 纯 Kotlin、无 Android 依赖，单元测试覆盖。
 */
data class TicTacToe(
    /** 9 格，0 空、1 人（X）、2 TA（O）。 */
    val board: List<Int> = List(9) { 0 },
    /** 轮到谁：1 = 人，2 = TA。 */
    val turn: Int = 1,
    /** 0 = 进行中；1 = 人赢；2 = TA 赢；3 = 平局。 */
    val winner: Int = 0,
    val moves: Int = 0,
) {
    val over: Boolean get() = winner != 0
    val emptyCells: List<Int> get() = board.indices.filter { board[it] == 0 }

    fun canPlay(cell: Int, who: Int = turn): Boolean =
        !over && cell in 0..8 && board[cell] == 0 && who == turn

    /** 落子；非法直接抛错（UI 必须先 canPlay）。 */
    fun play(cell: Int, who: Int = turn): TicTacToe {
        require(canPlay(cell, who)) { "这格不能下：cell=$cell who=$who turn=$turn board=$board" }
        val next = board.toMutableList().also { it[cell] = who }
        val w = winnerOf(next)
        return copy(
            board = next,
            turn = if (who == 1) 2 else 1,
            winner = w,
            moves = moves + 1,
        )
    }

    fun reset(): TicTacToe = TicTacToe()

    /**
     * P0-A 的**模拟对手**（还没接模型）：能赢就赢、能堵就堵，否则中心/角/边。
     * 返回 null 表示没得下（已结束）。
     */
    fun suggestedMove(forWho: Int = 2): Int? {
        if (over) return null
        val me = forWho
        val other = if (me == 1) 2 else 1
        winNow(me)?.let { return it }
        winNow(other)?.let { return it }
        val order = listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)
        return order.firstOrNull { board[it] == 0 }
    }

    private fun winNow(who: Int): Int? {
        for (line in WIN_LINES) {
            val cells = line.toList()
            val mine = cells.count { board[it] == who }
            val empty = cells.firstOrNull { board[it] == 0 } ?: continue
            if (mine == 2) return empty
        }
        return null
    }

    companion object {
        val WIN_LINES: List<IntArray> = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )

        fun winnerOf(b: List<Int>): Int {
            for (line in WIN_LINES) {
                val v = b[line[0]]
                if (v != 0 && v == b[line[1]] && b[line[1]] == b[line[2]]) return v
            }
            return if (b.none { it == 0 }) 3 else 0
        }
    }
}
