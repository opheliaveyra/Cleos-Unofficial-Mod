package com.cleo.cleos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.liquidGlass

/**
 * 按住说话时手指停在哪：**左 = 转文字，右 = 直接发语音，上滑 = null（取消）**。
 *
 * 单聊和群聊共用这一个判定 —— 以前群聊没有长按说话，就是这么漏掉的。
 */
internal enum class HoldTarget { Text, Voice }

/** 手指要滑多远才算"指到另一边" / "上滑取消"（两边一样，手感统一）。 */
private val HoldDistance = 72.dp

/**
 * **单聊和群聊共用的「按住说话」层**：盖在输入框上，长按开始录，左右滑选目标，上滑取消。
 *
 * 轻点不算长按：手指一碰就松会走 [onTap]（单聊里是聚焦输入框、把键盘弹出来）。
 * 抽成一个组件，是为了以后加输入功能时**不会又一次只加在单聊里**。
 */
@Composable
internal fun HoldToTalkLayer(
    onTap: () -> Unit,
    /** 长按到手了：返回 true 表示真的开始录了（没权限 / 打不开话筒就返回 false）。 */
    onStart: () -> Boolean,
    onMove: (HoldTarget?) -> Unit,
    onEnd: (HoldTarget?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = rememberUpdatedState(onStart)
    val move = rememberUpdatedState(onMove)
    val end = rememberUpdatedState(onEnd)
    val tap = rememberUpdatedState(onTap)
    Box(
        modifier.pointerInput(Unit) {
            val distance = HoldDistance.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val long = awaitLongPressOrCancellation(down.id)
                if (long == null) {
                    down.consume()
                    tap.value()
                    return@awaitEachGesture
                }
                long.consume()
                if (!start.value()) return@awaitEachGesture
                // **上滑取消、左滑转文字、右滑直接发。**
                // 没怎么动的默认还是转文字 —— 和单聊一直以来的行为一样，老习惯不用改。
                var target: HoldTarget? = HoldTarget.Text
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val up = down.position.y - change.position.y > distance
                    val dx = change.position.x - down.position.x
                    val now = when {
                        up -> null
                        dx <= -distance -> HoldTarget.Text
                        dx >= distance -> HoldTarget.Voice
                        else -> HoldTarget.Text
                    }
                    if (now != target) {
                        target = now
                        move.value(now)
                    }
                    change.consume()
                }
                end.value(target)
            }
        },
    )
}

/** Above the input while recording: how long, how loud, and what letting go does; or a short hint. */
@Composable
internal fun RecordingPill(
    backdrop: Backdrop,
    recording: Boolean,
    ms: Long,
    level: Float,
    /** 手指现在指着哪边：左边转文字、右边直接发语音；null 是上滑取消。 */
    target: HoldTarget?,
    cancelling: Boolean,
    hint: String?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    // 手指没动时算指着"转文字"（和原来那个行为一样），所以这里不看 null。
    val aimed = target ?: HoldTarget.Text
    Row(
        modifier
            .liquidGlass(backdrop, palette.input, GlassShape.Capsule)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!recording) {
            Text(hint.orEmpty(), color = palette.content, fontSize = 14.sp)
            return@Row
        }
        val tint = if (cancelling) palette.error else palette.accent
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp + 12.dp * level).background(tint, CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        val s = ms / 1000
        Text("${s / 60}:%02d".format(s % 60), color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        // 一个"按住说话"里塞两件事，所以要让手指去指：**往左是转文字，往右是原样发**。
        // 上滑取消还是老规矩。
        if (cancelling) {
            Text("松开取消", color = palette.error, fontSize = 14.sp)
        } else {
            HoldAim("← 转文字", aimed == HoldTarget.Text, palette.accentContent, palette.contentSecondary)
            Spacer(Modifier.width(10.dp))
            HoldAim("直接发 →", aimed == HoldTarget.Voice, palette.accentContent, palette.contentSecondary)
        }
    }
}

/** 两个目标中的一个：指到它了就亮起来。 */
@Composable
private fun HoldAim(text: String, aimed: Boolean, on: Color, off: Color) {
    Text(
        text,
        color = if (aimed) on else off,
        fontSize = 14.sp,
        fontWeight = if (aimed) FontWeight.SemiBold else FontWeight.Normal,
    )
}
