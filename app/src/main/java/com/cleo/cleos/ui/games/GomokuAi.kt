package com.cleo.cleos.ui.games

import com.cleo.cleos.CleosApp
import com.cleo.cleos.ai.ApiEndpoint
import com.cleo.cleos.ai.ApiMessage
import com.cleo.cleos.ai.ChatClient
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

/** 五子棋一步的结果：合法坐标，或者类型化的失败。 */
sealed interface GomokuMoveResult {
    data class Ok(val row: Int, val col: Int) : GomokuMoveResult
    data class Failed(val kind: MoveFailure, val why: String) : GomokuMoveResult
}

/**
 * P1-B：让受邀 TA 用模型决定五子棋落点。
 *
 * 边界与井字棋一致：AI 只决定行动；本地校验合法性。
 * 棋盘压缩成 15 行符号（`.` 空、`X` 玩家、`O` TA），只接受严格的 `row,col`；
 * 共享 chatClient + `UsageTag(purpose = game_gomoku)`；只对非法输出有限重试。
 */
object GomokuAi {
    const val DEFAULT_ATTEMPTS = 3
    const val TIMEOUT_MS = 60_000L
    const val MAX_ANSWER_CHARS = 200

    suspend fun askMove(
        app: CleosApp,
        session: GomokuSession,
        attempts: Int = DEFAULT_ATTEMPTS,
    ): GomokuMoveResult = withContext(Dispatchers.IO) {
        if (session.pending == null) {
            return@withContext GomokuMoveResult.Failed(MoveFailure.NotClaimed, "没有认领请求")
        }
        val c = app.container
        val ta = c.companions.get(session.taId)
            ?: return@withContext GomokuMoveResult.Failed(
                MoveFailure.TaMissing,
                "这个 TA 已经不在了（可能被删过）—— 重开一局，换一个 TA。",
            )
        val resolved = when (val route = c.registry.route(Feature.Chat)) {
            is RouteResult.Ready -> route.model
            is RouteResult.Broken -> return@withContext GomokuMoveResult.Failed(MoveFailure.NotConfigured, route.why)
        }
        val key = c.secrets.key(resolved.provider.baseUrl).orEmpty()
        if (key.isBlank()) {
            return@withContext GomokuMoveResult.Failed(MoveFailure.NoKey, ta.name.ifBlank { "TA" } + " 还没填 API Key。")
        }
        val endpoint = ApiEndpoint(resolved.provider.baseUrl, key, resolved.config.model)
        val tag = UsageTag(
            scope = TokenUsageEntity.SCOPE_TA,
            scopeId = session.taId,
            scopeName = ta.name.ifBlank { "TA" },
            purpose = "game_gomoku",
        )
        var last: GomokuMoveResult.Failed? = null
        for (attempt in 1..attempts) {
            val move = try {
                withTimeout(TIMEOUT_MS) { askOnce(c.chatClient, endpoint, tag, session.game, ta) }
            } catch (e: TimeoutCancellationException) {
                return@withContext GomokuMoveResult.Failed(
                    MoveFailure.Timeout,
                    "TA 想太久了（超过 " + TIMEOUT_MS / 1000 + " 秒），稍后再让它继续。",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext GomokuMoveResult.Failed(MoveFailure.Network, e.message ?: "请求失败")
            }
            if (move != null) return@withContext GomokuMoveResult.Ok(move.first, move.second)
            last = GomokuMoveResult.Failed(MoveFailure.IllegalMove, "TA 回的不是合法的 row,col（0-14 的坐标）")
            if (attempt == attempts) break
        }
        last ?: GomokuMoveResult.Failed(MoveFailure.IllegalMove, "TA 没有给出这一步")
    }

    private suspend fun askOnce(
        client: ChatClient,
        endpoint: ApiEndpoint,
        tag: UsageTag,
        game: Gomoku,
        ta: CompanionEntity,
    ): Pair<Int, Int>? {
        var answer = ""
        client.stream(
            endpoint = endpoint,
            messages = listOf(ApiMessage("user", prompt(game, ta))),
            usageTag = tag,
        ).collect { ev ->
            if (ev is ChatEvent.Delta) {
                answer += ev.text
                if (answer.length > MAX_ANSWER_CHARS) throw IllegalStateException("TA 回得太长")
            }
        }
        return parseMove(answer, game)
    }

    /** 15 行符号 + 明确坐标规则；带上 TA 的名字/设定（只影响说话方式）。 */
    internal fun prompt(game: Gomoku, ta: CompanionEntity): String = buildString {
        append("你在和我下五子棋。你是白棋 O，我是黑棋 X。15×15 棋盘，我先手。")
        if (ta.name.isNotBlank()) append("你的名字是「").append(ta.name).append("」。")
        val persona = ta.persona.trim()
        if (persona.isNotEmpty()) append("你的设定（只影响说话方式，走法规则不能变）：").append(persona.take(200))
        append("\n棋盘（一行一行，从上到下；. 是空位，X 是我，O 是你）：\n")
        for (row in 0 until GOMOKU_SIZE) {
            for (col in 0 until GOMOKU_SIZE) {
                append(when (game.at(row, col)) { 1 -> 'X'; 2 -> 'O'; else -> '.' })
            }
            append('\n')
        }
        append("坐标是 (行,列)，从左上角开始，行和列都是 0-14。")
        append("你只能落在空位上。")
        append("只回复一个坐标，格式严格为 \"行,列\"（例如 7,7），不要写别的任何字。")
    }

    /** 严格解析：整段 trim 后必须正好是 `row,col`，范围 0..14，且是空位。 */
    internal fun parseMove(answer: String, game: Gomoku): Pair<Int, Int>? {
        val s = answer.trim()
        val m = Regex("^(\\d{1,2}),(\\d{1,2})$").find(s) ?: return null
        val row = m.groupValues[1].toIntOrNull() ?: return null
        val col = m.groupValues[2].toIntOrNull() ?: return null
        if (!game.canPlay(row, col, 2)) return null
        return row to col
    }
}
