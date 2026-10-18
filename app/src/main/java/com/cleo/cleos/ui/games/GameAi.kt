package com.cleo.cleos.ui.games

import com.cleo.cleos.CleosApp
import com.cleo.cleos.ai.ApiEndpoint
import com.cleo.cleos.ai.ApiMessage
import com.cleo.cleos.ai.ChatEvent
import com.cleo.cleos.ai.UsageTag
import com.cleo.cleos.data.RouteResult
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.db.TokenUsageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/** 失败的类型：自动重试只看 [IllegalMove]，其余不重复付费。 */
enum class MoveFailure { NotConfigured, NoKey, TaMissing, Network, Timeout, IllegalMove, NotClaimed }

sealed interface MoveResult {
    data class Ok(val cell: Int) : MoveResult
    data class Failed(val kind: MoveFailure, val why: String) : MoveResult
}

/**
 * P0-C：让受邀 TA **用自己的模型**决定下一步。边界：AI 只决定行动。
 *
 * - 走 AppContainer 的**共享 chatClient**（诊断、用量回调都在里面），每次调用带 [UsageTag]，
 *   purpose = `game_tic_tac_toe`，归属受邀 TA；
 * - 只有「模型回的不是合法格」才自动再问（次数有界）；网络/配置/超时失败**不重复付费**；
 * - 取消（CancellationException）原样抛出，不变成普通失败；
 * - 严格解析：整段回答必须正好是一个能下的 0..8 数字，多字/多数字/负数/越界/占用/空全拒。
 */
object GameAi {
    /** 总调用次数（含第一次）；非法输出时最多再问 attempts-1 次。 */
    const val DEFAULT_ATTEMPTS = 3
    const val TIMEOUT_MS = 45_000L
    const val MAX_ANSWER_CHARS = 200

    suspend fun askMove(app: CleosApp, session: GameSession, attempts: Int = DEFAULT_ATTEMPTS): MoveResult =
        withContext(Dispatchers.IO) {
            if (session.pending == null) return@withContext MoveResult.Failed(MoveFailure.NotClaimed, "没有认领请求")
            val c = app.container
            val ta = c.companions.get(session.taId)
                ?: return@withContext MoveResult.Failed(
                    MoveFailure.TaMissing,
                    "这个 TA 已经不在了（可能被删过）—— 重开一局，换一个 TA。",
                )
            val resolved = when (val route = c.registry.route(Feature.Chat)) {
                is RouteResult.Ready -> route.model
                is RouteResult.Broken -> return@withContext MoveResult.Failed(MoveFailure.NotConfigured, route.why)
            }
            val key = c.secrets.key(resolved.provider.baseUrl).orEmpty()
            if (key.isBlank()) {
                return@withContext MoveResult.Failed(MoveFailure.NoKey, ta.name.ifBlank { "TA" } + " 还没填 API Key。")
            }
            val endpoint = ApiEndpoint(resolved.provider.baseUrl, key, resolved.config.model)
            val tag = UsageTag(
                scope = TokenUsageEntity.SCOPE_TA,
                scopeId = session.taId,
                scopeName = ta.name.ifBlank { "TA" },
                purpose = "game_tic_tac_toe",
            )
            var last: MoveResult.Failed? = null
            for (attempt in 1..attempts) {
                val cell = try {
                    withTimeout(TIMEOUT_MS) { askOnce(c.chatClient, endpoint, tag, session.game, ta) }
                } catch (e: TimeoutCancellationException) {
                    return@withContext MoveResult.Failed(
                        MoveFailure.Timeout,
                        "TA 想太久了（超过 " + TIMEOUT_MS / 1000 + " 秒），稍后再让它继续。",
                    )
                } catch (e: CancellationException) {
                    throw e // 取消不是失败
                } catch (e: Exception) {
                    return@withContext MoveResult.Failed(MoveFailure.Network, e.message ?: "请求失败")
                }
                if (cell != null) return@withContext MoveResult.Ok(cell)
                last = MoveResult.Failed(MoveFailure.IllegalMove, "TA 回的不是一个能下的格子（只接受 0-8 里的单个数字）")
                if (attempt == attempts) break
            }
            last ?: MoveResult.Failed(MoveFailure.IllegalMove, "TA 没有给出这一步")
        }

    private suspend fun askOnce(
        client: com.cleo.cleos.ai.ChatClient,
        endpoint: ApiEndpoint,
        tag: UsageTag,
        game: TicTacToe,
        ta: CompanionEntity,
    ): Int? {
        var answer = ""
        client.stream(
            endpoint = endpoint,
            messages = listOf(ApiMessage("user", prompt(game, ta))),
            usageTag = tag,
        ).collect { ev ->
            if (ev is ChatEvent.Delta) {
                answer += ev.text
                if (answer.length > MAX_ANSWER_CHARS) throw IllegalStateException("TA 回得太长，不像一个数字")
            }
        }
        return parseMove(answer, game)
    }

    /** 局面 → 一句紧凑的话（3×3 摆出来 + 明确列出可选数字），并带上 TA 的名字/设定。 */
    internal fun prompt(game: TicTacToe, ta: CompanionEntity): String = buildString {
        append("你在和对方下井字棋。你是 O，对方是 X。")
        if (ta.name.isNotBlank()) append("你的名字是「").append(ta.name).append("」。")
        val persona = ta.persona.trim()
        if (persona.isNotEmpty()) {
            append("你的设定（只影响说话方式，走法规则不能变）：").append(persona.take(300))
        }
        append("\n格子编号：\n0 1 2\n3 4 5\n6 7 8\n")
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
        append("只回复一个数字（0-8），不要写任何别的字、不要解释。")
    }

    /**
     * 严格解析：整段（去掉首尾空白）必须**正好**是一个能下的 0..8 数字。
     * 解释文字、多个数字、负号、越界、已占的格子、空输出 —— 全部拒绝。
     */
    internal fun parseMove(answer: String, game: TicTacToe): Int? {
        val s = answer.trim()
        if (s.length != 1) return null
        val cell = s[0] - '0'
        if (cell !in 0..8) return null
        return if (game.canPlay(cell, 2)) cell else null
    }
}
