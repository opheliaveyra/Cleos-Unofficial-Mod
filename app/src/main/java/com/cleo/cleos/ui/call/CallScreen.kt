package com.cleo.cleos.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.CallPhase
import com.cleo.cleos.R
import com.cleo.cleos.ai.CallState
import com.cleo.cleos.data.CallRecords
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.avatarLetter
import kotlinx.coroutines.delay
import com.cleo.cleos.ui.theme.CleosIcon

/** The red of a phone's hang-up button: the one thing on the screen that should look like it ends something. */
private val HangUpRed = Color(0xFFE5484D)

/** 紫雾手账：通话按钮的纸面、描边，和挂断用的低饱和莓粉。 */
private val ScrapPaper = Color(0xFFFBF7FF)
private val ScrapHangUp = Color(0xFFF0D8DE)
private val ScrapLine = Color(0xFFB9A6D6)

/**
 * A call with a TA (ai/Call.kt): their picture with rings that move with whoever is talking, how
 * long the call has gone on, and what is being said as words under it, for when the voice isn't
 * clear. A tap anywhere cuts the TA off; back asks before hanging up. The screen stays on: a call
 * on speaker is looked at.
 */
@Composable
fun CallScreen() {
    val c = appContainer()
    val call by c.calls.state.collectAsStateWithLifecycle()
    val st = call ?: return
    val level by c.calls.level.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    // 紫雾手账：通话页换成纸绘按钮、细线光环；原版主题一切照旧。
    val lavender = com.cleo.cleos.ui.theme.LocalVisualTheme.current == com.cleo.cleos.data.VisualTheme.LAVENDER
    var askHangUp by remember { mutableStateOf(false) }
    val ended = st.phase == CallPhase.Ended
    BackHandler(enabled = !ended) { askHangUp = true }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.answeredAt, st.endedAt) {
        while (st.answeredAt != null && st.endedAt == null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val talked = st.answeredAt?.let { (st.endedAt ?: now) - it }
    val canCut = st.phase == CallPhase.Thinking || st.phase == CallPhase.Speaking

    GlassPage(
        overlay = { page ->
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(visible = canCut, enter = fadeIn(), exit = fadeOut()) {
                    Text("点屏幕任意地方打断", color = palette.contentSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 22.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(if (lavender) 40.dp else 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 正在呼叫、还没接通时静音没有意义：只留挂断，免得按钮跳来跳去。
                    if (st.phase != CallPhase.Ringing) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (lavender) {
                                ScrapCallButton(
                                    res = if (st.muted) R.drawable.scrap_bubble_voice else R.drawable.scrap_voice,
                                    label = if (st.muted) "已静音" else "静音",
                                    contentDescription = if (st.muted) "取消静音" else "静音",
                                    paper = if (st.muted) ScrapHangUp else ScrapPaper,
                                    enabled = !ended,
                                ) { c.calls.mute(!st.muted) }
                            } else {
                                GlassIconButton(
                                    icon = if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                    contentDescription = if (st.muted) "取消静音" else "静音",
                                    onClick = { c.calls.mute(!st.muted) },
                                    backdrop = page,
                                    enabled = !ended,
                                    tint = if (st.muted) palette.error else palette.content,
                                    size = 64.dp,
                                )
                            }
                            Text(if (st.muted) "已静音" else "静音", color = palette.contentSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (lavender) {
                            ScrapCallButton(
                                res = R.drawable.scrap_call_end,
                                label = "挂断",
                                contentDescription = "挂断",
                                paper = ScrapHangUp,
                                enabled = !ended,
                            ) { c.calls.hangUp() }
                        } else {
                            Box(
                                Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(if (ended) HangUpRed.copy(alpha = 0.45f) else HangUpRed)
                                    .clickable(enabled = !ended, role = Role.Button) { c.calls.hangUp() },
                                contentAlignment = Alignment.Center,
                            ) {
                                CleosIcon(Icons.Rounded.CallEnd, contentDescription = "挂断", tint = Color.White, modifier = Modifier.size(34.dp))
                            }
                        }
                        Text("挂断", color = palette.contentSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null, enabled = canCut) { c.calls.interrupt() }
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            Rings(st, level) {
                Avatar(st.avatar, st.avatarEmoji ?: avatarLetter(st.name, "TA"), 116.dp)
            }
            Spacer(Modifier.height(22.dp))
            Text(st.name.ifEmpty { "TA" }, color = palette.content, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(status(st, talked), color = palette.contentSecondary, fontSize = 15.sp)
            Spacer(Modifier.height(36.dp))
            Captions(st)
        }
    }

    if (askHangUp) {
        AlertDialog(
            onDismissRequest = { askHangUp = false },
            title = { Text("挂断电话？") },
            confirmButton = {
                TextButton(onClick = {
                    askHangUp = false
                    c.calls.hangUp()
                }) { Text("挂断", color = HangUpRed) }
            },
            dismissButton = { TextButton(onClick = { askHangUp = false }) { Text("继续打") } },
        )
    }
}

/** How long, and what is happening: 03:12 · 在听. */
private fun status(st: CallState, talked: Long?): String {
    val what = when (st.phase) {
        CallPhase.Ringing -> return "正在呼叫…"
        CallPhase.Ended -> return if (talked != null) "通话结束 ${CallRecords.clock(talked)}" else "已挂断"
        CallPhase.Listening -> when {
            st.held -> "别的应用在出声，先等等"
            st.muted -> "已静音"
            else -> "在听"
        }
        CallPhase.Hearing -> "在听你说"
        CallPhase.Thinking -> "……"
        CallPhase.Speaking -> "${st.name.ifEmpty { "TA" }}在说"
    }
    return if (talked != null) "${CallRecords.clock(talked)} · $what" else what
}

/**
 * Rings round the picture: while it rings, a slow pulse; then they swell with the loudness of
 * whoever is talking.
 */
@Composable
private fun Rings(st: CallState, level: Float, content: @Composable () -> Unit) {
    val palette = LocalGlassPalette.current
    val lavender = com.cleo.cleos.ui.theme.LocalVisualTheme.current == com.cleo.cleos.data.VisualTheme.LAVENDER
    val loud by animateFloatAsState(level, tween(120), label = "level")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val beat by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "beat",
    )
    val ringing = st.phase == CallPhase.Ringing
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = 58.dp.toPx() + 6.dp.toPx()
            val reach = 36.dp.toPx()
            val color = palette.accent
            // 紫雾手账：不要扫描圈、不要厚重光晕 —— 只留一圈浅紫细线，带一点呼吸。
            if (lavender) {
                val breath = 0.5f + 0.5f * kotlin.math.sin(beat * 2f * kotlin.math.PI.toFloat())
                val ring = ScrapLine.copy(alpha = if (st.phase == CallPhase.Ended) 0.25f else 0.35f + 0.25f * breath)
                if (st.phase != CallPhase.Ended) {
                    drawCircle(ring, radius = base + 6.dp.toPx() + 2.dp.toPx() * breath, style = Stroke(1.dp.toPx()))
                }
            } else if (ringing) {
                // Two rings going out one after the other, as long as it rings.
                for (k in 0..1) {
                    val t = (beat + k * 0.5f) % 1f
                    drawCircle(color.copy(alpha = 0.45f * (1 - t)), radius = base + reach * t, style = Stroke(2.dp.toPx()))
                }
            } else if (st.phase != CallPhase.Ended) {
                drawCircle(color.copy(alpha = 0.16f + 0.2f * loud), radius = base + reach * loud * 0.9f)
                drawCircle(color.copy(alpha = 0.10f + 0.12f * loud), radius = base + reach * loud * 0.5f + 4.dp.toPx())
            }
        }
        content()
    }
}

/** What is being said, as words: the TA's sentence now, under what the person said last. */
@Composable
private fun Captions(st: CallState) {
    val palette = LocalGlassPalette.current
    val heard = st.heard
    val saying = st.saying
    val hint = when {
        st.phase == CallPhase.Listening && heard == null && !st.muted && !st.held -> "说吧，说完停一下，${st.name.ifEmpty { "TA" }}就会接着说"
        else -> null
    }
    if (heard == null && saying == null && hint == null && st.problem == null) return
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape.Rounded(24.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (heard != null) {
                Text("你：$heard", color = palette.contentSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 3)
            }
            if (saying != null) {
                if (heard != null) Spacer(Modifier.height(10.dp))
                Text(saying, color = palette.content, fontSize = 19.sp, lineHeight = 27.sp, textAlign = TextAlign.Center)
            }
            if (hint != null && saying == null) {
                if (heard != null) Spacer(Modifier.height(10.dp))
                Text(hint, color = palette.contentSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            st.problem?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = palette.error, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * 紫雾手账的通话按钮：暖白/莓粉纸面 + 紫绘图标 + 浅紫细描边，56dp 视觉、触控不小于 48dp。
 * 静音和挂断用**同一套**外形和材质，只是纸面颜色不同 —— 不出现「一个纸绘、一个纯色系统圆」。
 */
@Composable
private fun ScrapCallButton(
    res: Int,
    label: String,
    contentDescription: String,
    paper: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (enabled) paper else paper.copy(alpha = 0.5f))
                .border(1.dp, ScrapLine.copy(alpha = if (enabled) 0.9f else 0.4f), CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(res),
                contentDescription = contentDescription,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(label, color = Color(0xFF655A70), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
