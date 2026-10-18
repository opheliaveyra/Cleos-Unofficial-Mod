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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 井字棋 P0-A：邀请一个**真实的 TA**（从 Companions 列表来），人执 X 先手，TA 执 O。
 *
 * 现在 TA 的落子是**本地模拟**（能赢就赢/能堵就堵），页面上明确写着「模拟对手」，
 * 等接上 Provider 再换成真模型（任务书 P0-B）。本地状态机在 [TicTacToe] 里，模型不碰它。
 */
@Composable
fun TicTacToeScreen(onBack: () -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val companions by c.companions.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    val app = LocalContext.current.applicationContext as CleosApp
    var invited by remember { mutableStateOf<CompanionEntity?>(null) }
    var game by remember { mutableStateOf<TicTacToe?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun startWith(ta: CompanionEntity) {
        invited = ta
        game = TicTacToe()
        showInvite = false
    }

    /** 请 TA 落子：真模型（P0-B）。失败就明说，不替它下。 */
    fun taMove(after: TicTacToe) {
        thinking = true
        error = null
        scope.launch {
            when (val result = GameAi.askMove(app, after)) {
                is MoveResult.Ok -> game = after.play(result.cell, 2)
                is MoveResult.Failed -> error = result.why
            }
            thinking = false
        }
    }

    fun humanPlay(cell: Int) {
        val g = game ?: return
        if (thinking || g.over || g.turn != 1 || !g.canPlay(cell, 1)) return
        val after = g.play(cell, 1)
        game = after
        if (after.over) return
        taMove(after)
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
            // 占位，让标题居中
            Spacer(Modifier.width(56.dp))
        }

        val ta = invited
        if (ta == null) {
            GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("邀请 TA 一起玩", color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "TA 从你已有的角色里选。你执 X 先手，TA 执 O。",
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
                    TextButton(onClick = { game = TicTacToe() }) { Text("重开", color = palette.accentContent) }
                }
            }

            val g = game
            if (g != null) {
                Text(statusText(g, thinking, ta.name.ifBlank { "TA" }), color = palette.content, fontSize = 14.sp)
                error?.let { why ->
                    Text(why, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    TextButton(onClick = { taMove(g) }) { Text("让它再试一次", color = palette.accentContent) }
                }
                Board(g, enabled = !thinking) { humanPlay(it) }
                if (g.over) {
                    TextButton(onClick = { game = TicTacToe() }) { Text("再来一局", color = palette.accentContent) }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showInvite = true }) { Text("换一个 TA", color = palette.contentSecondary) }
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

private fun statusText(g: TicTacToe, thinking: Boolean, taName: String): String = when {
    g.winner == 1 -> "你赢了 🎉"
    g.winner == 2 -> taName + " 赢了"
    g.winner == 3 -> "平局"
    thinking -> taName + " 在想…"
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
                    val playable = enabled && mark == 0 && !game.over && game.turn == 1
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
