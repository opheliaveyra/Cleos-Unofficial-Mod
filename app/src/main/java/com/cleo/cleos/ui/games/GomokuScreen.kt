package com.cleo.cleos.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt

/**
 * 五子棋 P1：邀请真实 TA 对弈。
 *
 * 规则在 [Gomoku]（15×15，黑先，五连或平局，首版不禁手）；
 * TA 的落子走 [GomokuAi]（真模型，共享 chatClient + 用量标签）；请求归属在 [GomokuSession]。
 */
@Composable
fun GomokuScreen(onBack: () -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val companions by c.companions.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as CleosApp

    var invited by remember { mutableStateOf<CompanionEntity?>(null) }
    var session by remember { mutableStateOf<GomokuSession?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var interrupted by remember { mutableStateOf(false) }
    val busy = session?.pending != null

    fun persist(next: GomokuSession) {
        session = next
        scope.launch { c.gameSessions.saveGomoku(next) }
    }

    LaunchedEffect(Unit) {
        val restored = c.gameSessions.restoreGomoku() ?: return@LaunchedEffect
        val row = restored.row
        val ta = c.companions.get(row.taId)
        if (ta == null) {
            c.gameSessions.clear("gomoku")
            error = "上一局的 TA 已经不在了（可能被删过），这局就到这里 —— 重新邀请一个吧。"
            return@LaunchedEffect
        }
        val restoredSession = restored.session
        if (restoredSession == null) {
            c.gameSessions.clear("gomoku")
            error = "上一局的存档读不出来了，这一局已清掉；其它数据不受影响。"
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
        persist(GomokuSession.newGame(ta.id))
    }

    fun taMove(cur: GomokuSession) {
        if (cur.pending != null) return
        val requestId = UUID.randomUUID().toString()
        val claimed = cur.claimTaMove(requestId) ?: return
        session = claimed
        scope.launch { c.gameSessions.saveGomoku(claimed) }
        error = null
        scope.launch {
            val result = GomokuAi.askMove(app, claimed)
            val now = session
            if (now == null || now.id != claimed.id) return@launch
            when (result) {
                is GomokuMoveResult.Ok -> now.resolveTa(requestId, result.row, result.col)?.let { persist(it) }
                is GomokuMoveResult.Failed -> {
                    now.failTa(requestId)?.let { persist(it) }
                    error = result.why
                }
            }
        }
    }

    fun humanPlay(cur: GomokuSession, row: Int, col: Int) {
        if (busy) return
        val next = cur.playAsHuman(row, col) ?: return
        persist(next)
        if (!next.game.over) taMove(next)
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回", color = palette.accentContent) }
            Spacer(Modifier.weight(1f))
            Text("五子棋", color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
        }

        val ta = invited
        if (ta == null) {
            GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("邀请 TA 下一局五子棋", color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "你执黑先手，TA 执白后手。15×15，五连获胜；首版不禁手（三三、四四都按普通胜负）。",
                        color = palette.contentSecondary, fontSize = 13.sp,
                    )
                    TextButton(onClick = { showInvite = true }) { Text("选一个 TA", color = palette.accentContent) }
                }
            }
        } else {
            GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 28.dp)
                    Column(Modifier.weight(1f)) {
                        Text(ta.name.ifBlank { "TA" }, color = palette.content, fontSize = 14.sp)
                        Text("你 = 黑（先手） · TA = 白（真模型）", color = palette.contentSecondary, fontSize = 11.sp)
                    }
                    TextButton(enabled = !busy, onClick = {
                        error = null
                        interrupted = false
                        persist((session ?: GomokuSession.newGame(ta.id)).restart())
                    }) { Text("重开", color = palette.accentContent) }
                }
            }

            val s = session
            if (s != null) {
                Text(statusText(s.game, busy, ta.name.ifBlank { "TA" }), color = palette.content, fontSize = 14.sp)
                if (interrupted && !busy) {
                    Text(
                        "上次的棋局被打断了（轮到 TA 还没走）—— 不会自动重试，点下面继续。",
                        color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    TextButton(enabled = !s.game.over, onClick = { interrupted = false; taMove(s) }) {
                        Text("继续 TA 的回合", color = palette.accentContent)
                    }
                }
                error?.let { why ->
                    Text(why, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                    TextButton(enabled = !busy, onClick = { taMove(s) }) { Text("让它再试一次", color = palette.accentContent) }
                }
                GomokuBoard(s.game, enabled = !busy && !s.game.over && s.game.turn == 1) { row, col ->
                    humanPlay(s, row, col)
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
                            Avatar(item.avatar, item.avatarEmoji ?: avatarLetter(item.name, "TA"), 28.dp)
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

private fun statusText(g: Gomoku, busy: Boolean, taName: String): String = when {
    g.winner == 1 -> "你赢了 🎉"
    g.winner == 2 -> taName + " 赢了"
    g.winner == 3 -> "平局"
    busy -> taName + " 在想…"
    g.turn == 1 -> "轮到你（黑）"
    else -> taName + " 的回合（白）"
}

/**
 * 15×15 棋盘：棋子落在**线与线的交叉点**上（不是格子里）。
 * 画布留半格边距，15 条横线 + 15 条竖线，交叉点间距 = span/14；
 * 点击取最近的交叉点；最近一步画高亮圈。
 */
@Composable
private fun GomokuBoard(game: Gomoku, enabled: Boolean, onCell: (Int, Int) -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            // 五子棋棋盘用浅木色（跟围棋/五子棋的传统观感一致），不再是白色玻璃。
            .background(Color(0xFFEAD9B8))
            .pointerInput(enabled) {
                detectTapGestures { offset ->
                    if (!enabled) return@detectTapGestures
                    val margin = size.width / 30f
                    val step = (size.width - 2 * margin) / (GOMOKU_SIZE - 1)
                    val col = ((offset.x - margin) / step).roundToInt().coerceIn(0, GOMOKU_SIZE - 1)
                    val row = ((offset.y - margin) / step).roundToInt().coerceIn(0, GOMOKU_SIZE - 1)
                    onCell(row, col)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val margin = size.width / 30f
            val step = (size.width - 2 * margin) / (GOMOKU_SIZE - 1)
            val line = Color(0x998A6A3E) // 深棕色的线，落在木色底上更清楚
            // 15 条线；棋子落在它们的交叉点上
            for (i in 0 until GOMOKU_SIZE) {
                val p = margin + i * step
                drawLine(line, Offset(p, margin), Offset(p, size.height - margin), 1f)
                drawLine(line, Offset(margin, p), Offset(size.width - margin, p), 1f)
            }
            for (row in 0 until GOMOKU_SIZE) {
                for (col in 0 until GOMOKU_SIZE) {
                    val v = game.at(row, col)
                    if (v == 0) continue
                    val center = Offset(margin + col * step, margin + row * step)
                    drawCircle(
                        color = if (v == 1) Color(0xFF23232B) else Color.White,
                        radius = step * 0.42f,
                        center = center,
                    )
                    if (v == 2) {
                        drawCircle(Color(0xFFB9B9C6), radius = step * 0.42f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                    }
                }
            }
            if (game.lastRow >= 0 && game.lastCol >= 0) {
                val center = Offset(margin + game.lastCol * step, margin + game.lastRow * step)
                drawCircle(
                    color = palette.accentContent.copy(alpha = 0.9f),
                    radius = step * 0.52f,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f),
                )
            }
        }
    }
}
