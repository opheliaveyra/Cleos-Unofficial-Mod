package com.cleo.cleos.ui.games

/** 五子棋棋盘边长（15×15）。 */
const val GOMOKU_SIZE = 15

/**
 * 五子棋 P1-A：纯 Kotlin 规则（15×15，黑先）。
 *
 * 本地规则是唯一权威：横、竖、两条斜线，任意方向连续五子即胜；满盘平局。
 * **首版不使用禁手规则**（三三、四四、长连都算普通胜负），UI 里会说明。
 */
data class Gomoku(
    val board: List<Int> = List(GOMOKU_SIZE * GOMOKU_SIZE) { 0 },
    /** 1 = 黑（玩家先手），2 = 白（TA 后手）。 */
    val turn: Int = 1,
    /** 0 = 进行中，1 = 黑胜，2 = 白胜，3 = 平局。 */
    val winner: Int = 0,
    val moves: Int = 0,
    /** 最近一步的坐标（-1 表示还没走过），UI 用来高亮。 */
    val lastRow: Int = -1,
    val lastCol: Int = -1,
) {
    val over: Boolean get() = winner != 0

    fun at(row: Int, col: Int): Int =
        if (inBounds(row, col)) board[row * GOMOKU_SIZE + col] else 0

    fun inBounds(row: Int, col: Int): Boolean =
        row in 0 until GOMOKU_SIZE && col in 0 until GOMOKU_SIZE

    /** 空位、未结束、轮到 [who]，才能落子。 */
    fun canPlay(row: Int, col: Int, who: Int): Boolean =
        !over && who == turn && inBounds(row, col) && board[row * GOMOKU_SIZE + col] == 0

    fun play(row: Int, col: Int, who: Int): Gomoku {
        require(canPlay(row, col, who)) { "非法落子 ($row,$col) for $who" }
        val next = board.toMutableList()
        next[row * GOMOKU_SIZE + col] = who
        val played = moves + 1
        val won = if (hasFive(next, row, col, who)) who else 0
        return copy(
            board = next,
            turn = if (who == 1) 2 else 1,
            winner = if (won != 0) won else if (played == GOMOKU_SIZE * GOMOKU_SIZE) 3 else 0,
            moves = played,
            lastRow = row,
            lastCol = col,
        )
    }

    /** 所有空位（横坐标行优先），TA 请求时可作为合法落点集合。 */
    fun emptyCells(): List<Pair<Int, Int>> = buildList {
        for (row in 0 until GOMOKU_SIZE) {
            for (col in 0 until GOMOKU_SIZE) {
                if (board[row * GOMOKU_SIZE + col] == 0) add(row to col)
            }
        }
    }

    companion object {
        /** 落点四个方向（横/竖/正斜/反斜）任意一边连成五子即胜。 */
        private val DIRECTIONS = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)

        internal fun hasFive(board: List<Int>, row: Int, col: Int, who: Int): Boolean {
            for ((dr, dc) in DIRECTIONS) {
                var count = 1
                var r = row + dr
                var c = col + dc
                while (inBoundsOf(r, c) && board[r * GOMOKU_SIZE + c] == who) {
                    count++
                    r += dr
                    c += dc
                }
                r = row - dr
                c = col - dc
                while (inBoundsOf(r, c) && board[r * GOMOKU_SIZE + c] == who) {
                    count++
                    r -= dr
                    c -= dc
                }
                if (count >= 5) return true
            }
            return false
        }

        private fun inBoundsOf(row: Int, col: Int): Boolean =
            row in 0 until GOMOKU_SIZE && col in 0 until GOMOKU_SIZE
    }
}
