package com.cleo.cleos.games

import com.cleo.cleos.ai.*
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.db.TokenUsageEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID

/** Process-wide coordinator. No screen effect starts a model request. */
class Games(private val dao: GameDao, private val db: AppDatabase,
    private val registry: ModelRegistry, private val secrets: SecretStore,
    private val client: ChatClient, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private val _session = MutableStateFlow<GameSession?>(null)
    val session = _session.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    val problem = _problem.asStateFlow()
    private var ticket: String? = null
    private var request: Job? = null

    init {
        scope.launch {
            try {
                _session.value = dao.get()?.let { TicTacToe.validate(json.decodeFromString<GameSession>(it.payload)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _problem.value = "棋局暂时无法读取，可重新开局；原记录未被覆盖"
            } finally { _ready.value = true; loaded.complete(Unit) }
        }
    }
    private suspend fun save(next: GameSession?) = withContext(NonCancellable) {
        if (next == null) dao.clear() else dao.put(GameRow(payload = json.encodeToString(next)))
        _session.value = next
    }
    private fun action(block: suspend () -> Unit) {
        scope.launch {
            loaded.await()
            try { lock.withLock { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _problem.value = e.message ?: "棋局操作失败，请重试" }
        }
    }
    /** Confirming the invitation starts the game; never pretends that the model accepted. */
    fun start(taId: Long) = action {
        val ta = db.companions().get(taId) ?: error("这个 TA 已不存在，请重新选择")
        invalidate()
        save(GameSession(UUID.randomUUID().toString(), taId, playerTaCreatedAt = ta.createdAt))
        _problem.value = null
    }
    fun reset() = action {
        invalidate()
        save(null)
        _problem.value = null
    }
    /** A backup can replace TA IDs. Never let an old request act on imported identities. */
    suspend fun stopForRestore() {
        loaded.await()
        lock.withLock { invalidate(); save(null); _problem.value = null }
    }
    private fun invalidate() {
        ticket = null
        request?.cancel()
        request = null
        _busy.value = false
    }
    fun move(cell: Int) = action {
        check(!_busy.value) { "TA 正在落子，请稍等" }
        val old = _session.value ?: return@action
        save(old.move(cell, "X"))
        _problem.value = null
        if (_session.value?.result == null) beginRequest()
    }
    fun retry() = action { if (!_busy.value) beginRequest() }

    /** Called under lock: a ticket is installed before another tap can start anything. */
    private fun beginRequest() {
        val asked = _session.value ?: return
        if (asked.turn != "O" || asked.result != null) return
        val mine = UUID.randomUUID().toString()
        ticket = mine
        _busy.value = true
        _problem.value = null
        request = scope.launch {
            try {
                val ta = db.companions().get(asked.playerTaId) ?: error("这个 TA 已不存在，请重新邀请")
                check(ta.createdAt == asked.playerTaCreatedAt) { "TA 身份已改变，请重新邀请" }
                // Latest Cleos uses a global chat route, rather than per-TA legacy endpoints.
                val use = when (val route = registry.route(Feature.Chat)) {
                    is RouteResult.Ready -> route.model
                    is RouteResult.Broken -> error(route.why)
                }
                ModelRoutes.unsupportedKind(use.provider)?.let { error(it) }
                val key = secrets.key(use.baseUrl)?.takeIf { it.isNotBlank() } ?: error("请先在设置中为聊天模型配置密钥")
                var cell: Int? = null
                // Retry malformed/occupied actions once only. Network errors require an explicit retry.
                for (attempt in 0..1) {
                    val text = StringBuilder()
                    withTimeout(60_000) {
                        client.stream(ApiEndpoint(use.baseUrl, key, use.model), listOf(
                            ApiMessage("system", "你是${ta.name.ifBlank { "TA" }}，正在与用户玩井字棋。角色资料：${ta.persona.take(4000)}\n" +
                                "你执 O，用户执 X。只输出允许动作中的一个数字（0..8），不输出解释、代码块或胜负判断。"),
                            ApiMessage("user", "棋盘按从左到右、从上到下编号 0..8：${asked.board.map { it.ifEmpty { "." } }}。" +
                                "允许动作：${asked.legal}。${if (attempt == 1) "上次输出不合法，请严格只回复一个允许的数字。" else ""}")),
                            thinking = ta.deepThinking, temperature = use.temperature, maxTokens = use.maxTokens,
                            usageTag = UsageTag(TokenUsageEntity.SCOPE_TA, ta.id, ta.name, "game_tic_tac_toe", ta.name))
                            .collect { if (it is ChatEvent.Delta) {
                                check(text.length + it.text.length <= 256) { "TA 的落子输出过长，请重试" }
                                text.append(it.text)
                            } }
                    }
                    cell = runCatching { TicTacToe.action(text.toString(), asked) }.getOrNull()
                    if (cell != null) break
                }
                val chosen = cell ?: error("TA 连续返回了不合法的落子，棋盘未改变，请重试")
                lock.withLock {
                    val current = _session.value
                    if (ticket == mine && current?.accepts(asked.sessionId, asked.revision) == true) {
                        check(db.companions().get(ta.id) == ta) { "TA 资料已改变，本次未落子，请重试" }
                        check(registry.route(Feature.Chat) == RouteResult.Ready(use)) { "模型配置已改变，本次未落子，请重试" }
                        save(current.move(chosen, "O"))
                    }
                }
            } catch (e: TimeoutCancellationException) {
                lock.withLock { if (ticket == mine) _problem.value = "TA 落子超时，棋盘未改变，请重试" }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                lock.withLock { if (ticket == mine) _problem.value = e.message ?: "TA 落子失败，请重试" }
            } finally {
                withContext(NonCancellable) { lock.withLock {
                    if (ticket == mine) { ticket = null; request = null; _busy.value = false }
                } }
            }
        }
    }
}
