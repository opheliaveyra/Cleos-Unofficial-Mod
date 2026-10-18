package com.cleo.cleos.ui.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.*

@Composable
fun GamesHomeCard(onOpen: () -> Unit) {
    val p = LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth().clickable(onClickLabel = "打开小游戏中心", onClick = onOpen),
        contentPadding = PaddingValues(16.dp)) {
        Column {
            Text("小游戏中心  ›", color = p.content, fontSize = 16.sp)
            Text("邀请 TA，一起下一局井字棋", color = p.contentSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun GamePage(title: String, onBack: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    GlassPage(overlay = { page ->
        GlassTopBar(title, page, leading = { TextButton(onBack) { Text("返回") } })
    }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = TopBarHeight)
            .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
            content = body)
    }
}

@Composable
fun GamesHomeScreen(onBack: () -> Unit, onTicTacToe: () -> Unit) {
    val p = LocalGlassPalette.current
    GamePage("小游戏中心", onBack) {
        GlassSurface(Modifier.fillMaxWidth().clickable(onClick = onTicTacToe), contentPadding = PaddingValues(20.dp)) {
            Column {
                Text("井字棋  X · O", color = p.content, fontSize = 22.sp)
                Text("三子连线获胜 · 邀请一位 TA 陪玩", color = p.contentSecondary)
            }
        }
    }
}

@Composable
fun TicTacToeScreen(onBack: () -> Unit) {
    val c = appContainer()
    val p = LocalGlassPalette.current
    val game by c.games.session.collectAsStateWithLifecycle()
    val busy by c.games.busy.collectAsStateWithLifecycle()
    val ready by c.games.ready.collectAsStateWithLifecycle()
    val problem by c.games.problem.collectAsStateWithLifecycle()
    val companions by c.companions.all.collectAsStateWithLifecycle(emptyList())
    val current by c.companions.current.collectAsStateWithLifecycle(null)
    var inviting by rememberSaveable { mutableStateOf(false) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    val ta = companions.firstOrNull { it.id == game?.playerTaId && it.createdAt == game?.playerTaCreatedAt }
    GamePage("井字棋", onBack) {
        if (!ready) {
            CircularProgressIndicator()
        } else if (game == null) {
            Text("邀请 TA 一起玩", color = p.content, fontSize = 22.sp)
            Text("你执 X 先手，TA 执 O 后手。确认邀请即开始对局。", color = p.contentSecondary)
            Button({ inviting = true }, enabled = companions.isNotEmpty()) { Text("选择 TA") }
            if (companions.isEmpty()) Text("还没有 TA，请先到主页添加。", color = p.contentSecondary)
        } else {
            val s = game!!
            Text("我 X · ${ta?.name?.ifBlank { "TA" } ?: "已删除的 TA"} O", color = p.content, fontSize = 20.sp)
            val status = when (s.result) {
                "X" -> "你赢了！"
                "O" -> "TA 赢了，再来一局？"
                "draw" -> "平局！"
                else -> if (busy) "TA 正在思考…" else if (s.turn == "X") "轮到你落子" else "等待 TA 落子，点击继续"
            }
            Text(status, color = p.accentContent)
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { col ->
                            val cell = row * 3 + col
                            val mark = s.board[cell]
                            OutlinedButton(onClick = { c.games.move(cell) },
                                enabled = !busy && s.turn == "X" && cell in s.legal && ta != null,
                                modifier = Modifier.weight(1f).aspectRatio(1f).semantics {
                                    contentDescription = "第 ${row + 1} 行第 ${col + 1} 列，${mark.ifEmpty { "空格" }}"
                                }, contentPadding = PaddingValues(0.dp)) {
                                Text(mark, fontSize = 32.sp, color = p.content)
                            }
                        }
                    }
                }
            }
            if (s.result == null && s.turn == "O" && !busy && ta != null) {
                Button({ c.games.retry() }) { Text(if (problem == null) "继续 TA 回合" else "重试 TA 落子") }
            }
            if (ta == null) Text("原来的 TA 已不存在，请重新邀请。", color = p.error)
            TextButton({ confirmingReset = true }) { Text("重新开局 / 更换 TA") }
            Text("退出后棋局会保留；每次模型请求最多等待 60 秒，非法落子最多重试一次。", color = p.contentSecondary, fontSize = 12.sp)
        }
        problem?.let { Text(it, color = p.error) }
        if (ready && game == null && problem != null) TextButton({ c.games.reset() }) { Text("清除损坏的存档") }
        Text("使用设置中的聊天模型，模型落子会消耗 API 用量。棋局仅保存在当前设备，暂不随备份导出。", color = p.contentSecondary, fontSize = 12.sp)
    }
    if (inviting) InviteTaDialog(companions, current?.id, { inviting = false }) {
        inviting = false
        c.games.start(it)
    }
    if (confirmingReset) AlertDialog(onDismissRequest = { confirmingReset = false },
        title = { Text("结束当前棋局？") }, text = { Text("当前棋局将被清除，正在等待的落子也会取消。") },
        confirmButton = { TextButton({ confirmingReset = false; c.games.reset() }) { Text("重新邀请") } },
        dismissButton = { TextButton({ confirmingReset = false }) { Text("继续这一局") } })
}

@Composable
private fun InviteTaDialog(companions: List<CompanionEntity>, currentId: Long?, onCancel: () -> Unit, onInvite: (Long) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(currentId ?: companions.firstOrNull()?.id) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("邀请 TA 一起玩") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("确认后开始对局，不会请求或模拟 TA 接受邀请。")
            companions.forEach { ta ->
                Row(Modifier.fillMaxWidth().clickable { selected = ta.id }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 32.dp)
                    Text(ta.name.ifBlank { "TA" }, modifier = Modifier.weight(1f))
                    RadioButton(selected == ta.id, onClick = { selected = ta.id })
                }
            }
        }
    }, confirmButton = {
        TextButton({ selected?.let(onInvite) }, enabled = companions.any { it.id == selected }) { Text("邀请并开始") }
    }, dismissButton = { TextButton(onCancel) { Text("取消") } })
}
