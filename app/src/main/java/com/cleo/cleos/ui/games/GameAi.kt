package com.cleo.cleos.ui.games

import com.cleo.cleos.CleosApp
import com.cleo.cleos.ai.ApiEndpoint
import com.cleo.cleos.ai.ApiMessage
import com.cleo.cleos.ai.ChatClient
import com.cleo.cleos.ai.ChatEvent
import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.RouteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** 问 TA 下哪儿的结果：给个格子，或者说清楚为什么没有 —— 不偷偷替它下。 */
sealed interface MoveResult {
    data class Ok(val cell: Int) : MoveResult
    data class Failed(val why: String) : MoveResult
}

/**
 * P0-B：让 TA 真的用模型决定下一步（任务书边界：**AI 只决定行动**）。
 *
 * 这里只负责：把局面压成一句紧凑的话 → 调 TA 的模型 → 只接受 0..8 里一个能下的格子。
 * 合法性（空格、回合、结束）仍由 [TicTacToe.canPlay] 在本地判定；模型说别的就拒绝并说清楚。
 */
object GameAi {
    private val http = OkHttpClient()

    /**
     * 问一步。小模型经常回被占的格子，所以：只对「回了非法格」自动重试，
     * 网络/未配置这类失败直接返回（重试也没用）。最多问 [attempts] 次。
     */
    suspend fun askMove(app: CleosApp, game: TicTacToe, attempts: Int = 3): MoveResult {
        var last: MoveResult.Failed? = null
        repeat(attempts) {
            val one = askOnce(app, game)
            if (one is MoveResult.Ok) return one
            last = one as MoveResult.Failed
            if (!last.why.startsWith("TA 回的不是")) return last
        }
        return last ?: MoveResult.Failed("TA 没有给出这一步")
    }

    private suspend fun askOnce(app: CleosApp, game: TicTacToe): MoveResult = withContext(Dispatchers.IO) {
        val c = app.container
        val resolved = when (val route = c.registry.route(Feature.Chat)) {
            is RouteResult.Ready -> route.model
            is RouteResult.Broken -> return@withContext MoveResult.Failed(route.why)
        }
        val key = c.secrets.key(resolved.provider.baseUrl).orEmpty()
        if (key.isBlank()) return@withContext MoveResult.Failed("这个 TA 还没填 API Key。")
        val endpoint = ApiEndpoint(resolved.provider.baseUrl, key, resolved.config.model)
        var answer = ""
        val failure = runCatching {
            ChatClient(http).stream(
                endpoint = endpoint,
                messages = listOf(ApiMessage("user", prompt(game))),
            ).collect { ev -> if (ev is ChatEvent.Delta) answer += ev.text }
        }.exceptionOrNull()
        if (failure != null) return@withContext MoveResult.Failed(failure.message ?: "请求失败")
        val cell = parseMove(answer, game)
            ?: return@withContext MoveResult.Failed("TA 回的不是 0-8 的合法格子：" + answer.trim().take(40))
        MoveResult.Ok(cell)
    }

    /** 局面 → 一句紧凑的话（3×3 摆出来 + 明确列出可选数字），模型只需要回一个数字。 */
    internal fun prompt(game: TicTacToe): String = buildString {
        append("你在和我下井字棋。你是 O，我是 X。格子编号：\n")
        append("0 1 2\n3 4 5\n6 7 8\n")
        append("现在的棋盘（空格显示它的编号）：\n")
        for (row in 0..2) {
            for (col in 0..2) {
                val i = row * 3 + col
                append(when (game.board[i]) { 1 -> "X"; 2 -> "O"; else -> i.toString() })
                append(' ')
            }
            append('\n')
        }
        append("你只能从这些数字里选一个落子：").append(game.emptyCells.joinToString(" ")).append("\n")
        append("只回复其中列出的一个数字，不要写别的任何字。")
    }

    /** 只认 0..8 里**现在能下**的格子；别的数字、越界、已占都拒掉。纯函数，可单测。 */
    internal fun parseMove(answer: String, game: TicTacToe): Int? {
        for (m in Regex("\\d+").findAll(answer)) {
            val cell = m.value.toIntOrNull() ?: continue
            if (game.canPlay(cell, 2)) return cell
        }
        return null
    }
}
