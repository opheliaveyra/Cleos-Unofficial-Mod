package com.cleo.cleos.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.CleosApp
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.avatarLetter
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 井字棋 P0-B/C：邀请一个**真实的 TA**，人执 X 先手，TA 执 O。
 *
 * TA 的落子走 [GameAi]（真实模型，走共享 chatClient、带用量标签）。
 * 一局的状态与请求认领在 [GameSession] 里：双击不会发两次请求；重开/换 TA 之后，
 * 旧请求的结果回来也**不会覆盖新局**（id/revision 对不上就丢弃）。
 */
@Composable
fun TicTacToeScreen(onBack: () -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val companions by c.companions.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as CleosApp

    var invited by remember { mutableStateOf<CompanionEntity?>(null) }
    var session by remember { mutableStateOf<GameSession?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 上次退出时有一笔请求在途（进程被杀）：恢复后**不自动重试**。 */
    var interrupted by remember { mutableStateOf(false) }
    val busy = session?.pending != null

    fun persist(next: GameSession) {
        session = next
        scope.launch { c.gameSessions.save(next) }
    }

    // 进页面恢复最近一局；TA 被删或存档坏了都明说，不默默清掉别的数据。
    LaunchedEffect(Unit) {
        val restored = c.gameSessions.restore() ?: return@LaunchedEffect
        val row = restored.row
        val ta = c.companions.get(row.taId)
        if (ta == null) {
            c.gameSessions.clear("tic_tac_toe")
            error = "上一局的 TA 已经不在了（可能被删过），这局就到这里 —— 重新邀请一个吧。"
            return@LaunchedEffect
        }
        val restoredSession = restored.session
        if (restoredSession == null) {
            c.gameSessions.clear("tic_tac_toe")
            error = "上一局的存档读不出来了，这一局已清掉；聊天、日记等其它数据不受影响。"
            return@LaunchedEffect
        }
        invited = ta
        session = restoredSession
        interrupted = row.pending && !restoredSession.game.over
    }

    fun startWith(ta: CompanionEntity) {
        invited = ta
        error = null
        interrupted = false
        showInvite = false
        persist(GameSession.newGame(ta.id))
    }

    /** 认领一次请求再发（防双击）；结果回来时核对局 id，旧局的回复直接丢。 */
    fun taMove(cur: GameSession) {
        if (cur.pending != null) return
        val requestId = UUID.randomUUID().toString()
        val claimed = cur.claimTaMove(requestId) ?: return
        session = claimed
        // 先把"有请求在途"存下去：进程被杀后恢复时才知道这局被打断了。
        scope.launch { c.gameSessions.save(claimed) }
        error = null
        scope.launch {
            val result = GameAi.askMove(app, claimed)
            val now = session
            if (now == null || now.id != claimed.id) return@launch // 期间重开/换 TA：丢弃
            when (result) {
                is MoveResult.Ok -> now.resolveTa(requestId, result.cell)?.let { persist(it) }
                is MoveResult.Failed -> {
                    now.failTa(requestId)?.let { persist(it) }
                    error = result.why
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回", color = palette.accentContent) }
            Spacer(Modifier.weight(1f))
            Text("井字棋", color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
        }

        val ta = invited
        if (ta == null) {
            GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("邀请 TA 一起玩", color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "TA 从你已有的角色里选。你执 X 先手，TA 执 O；TA 的落子会真的调用它的模型。",
                        color = palette.contentSecondary, fontSize = 13.sp,
                    )
                    TextButton(onClick = { showInvite = true }) { Text("选一个 TA", color = palette.accentContent) }
                }
            }
        } else {
            GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 30.dp)
                    Column(Modifier.weight(1f)) {
                        Text(ta.name.ifBlank { "TA" }, color = palette.content, fontSize = 14.sp)
                        Text(
                            "你 = X（先手） · TA = O（会真的调模型来下）",
                            color = palette.contentSecondary, fontSize = 11.sp,
                        )
                    }
                    TextButton(enabled = !busy, onClick = {
                        error = null
                        interrupted = false
                        persist((session ?: GameSession.newGame(ta.id)).restart())
                    }) { Text("重开", color = palette.accentContent) }
                }
            }

            val s = session
            if (s != null) {
                Text(
                    statusText(s.game, busy, ta.name.ifBlank { "TA" }),
                    color = palette.content,
                    fontSize = 14.sp,
                )
                if (interrupted && !busy) {
                    Text(
                        "上次的棋局被打断了（轮到 TA 还没走）—— 不会自动重试，点下面继续。",
                        color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                    TextButton(enabled = !s.game.over, onClick = {
                        interrupted = false
                        taMove(s)
                    }) { Text("继续 TA 的回合", color = palette.accentContent) }
                }
                error?.let { why ->
                    Text(why, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    TextButton(enabled = !busy, onClick = { taMove(s) }) {
                        Text("让它再试一次", color = palette.accentContent)
                    }
                }
                Board(s.game, enabled = !busy && !s.game.over && s.game.turn == 1) { cell ->
                    humanPlay(s, cell, apply = { persist(it) }, thenTa = { taMove(it) })
                }
                if (s.game.over) {
                    TextButton(onClick = { error = null; interrupted = false; persist(s.restart()) }) {
                        Text("再来一局", color = palette.accentContent)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = { showInvite = true }) {
                Text("换一个 TA", color = palette.contentSecondary)
            }
        }
    }

    if (showInvite) {
        Dialog(onDismissRequest = { showInvite = false }) {
            GlassSurface(Modifier.fillMaxWidth(), style = palette.card, shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("邀请谁一起玩", color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (companions.isEmpty()) {
                        Text("还没有角色。先去聊天页建一个 TA。", color = palette.contentSecondary, fontSize = 13.sp)
                    }
                    companions.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { startWith(item) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Avatar(item.avatar, item.avatarEmoji ?: avatarLetter(item.name, "TA"), 30.dp)
                            Text(item.name.ifBlank { "TA" }, color = palette.content, modifier = Modifier.weight(1f))
                            if (invited?.id == item.id) Text("正在玩", color = palette.contentSecondary, fontSize = 11.sp)
                        }
                    }
                    TextButton(onClick = { showInvite = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("取消", color = palette.contentSecondary)
                    }
                }
            }
        }
    }
}

/** 人落子；成功后交给 TA（[taMove] 会认领请求）。 */
private fun humanPlay(cur: GameSession, cell: Int, apply: (GameSession) -> Unit, thenTa: (GameSession) -> Unit) {
    val next = cur.playAsHuman(cell) ?: return
    apply(next)
    if (!next.game.over) thenTa(next)
}

private fun statusText(g: TicTacToe, busy: Boolean, taName: String): String = when {
    g.winner == 1 -> "你赢了 🎉"
    g.winner == 2 -> taName + " 赢了"
    g.winner == 3 -> "平局"
    busy -> taName + " 在想…"
    g.turn == 1 -> "轮到你（X）"
    else -> taName + " 的回合（O）"
}

/** 3×3 棋盘：能下的格子高亮一点，已下的画 X / O。 */
@Composable
private fun Board(game: TicTacToe, enabled: Boolean, onCell: (Int) -> Unit) {
    val palette = LocalGlassPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until 3) {
                    val cell = row * 3 + col
                    val mark = game.board[cell]
                    val playable = enabled && mark == 0
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (playable) palette.card.tint.copy(alpha = 0.5f) else palette.card.tint.copy(alpha = 0.26f))
                            .clickable(enabled = playable) { onCell(cell) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mark != 0) {
                            Text(
                                if (mark == 1) "✕" else "○",
                                color = if (mark == 1) palette.accentContent else palette.contentSecondary,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
