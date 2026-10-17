package com.cleo.cleos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.LocalGlassPalette

/**
 * 输入栏下面的功能面板：**按屏幕宽度换行的网格**，功能多了就在面板里滚动。
 *
 * 单聊和群聊共用这一个 —— 以前两边各写各的：单聊是一整行塞不下的 chip，
 * 手机上直接被裁掉半截（截图里那个只露出一条的按钮就是这么来的）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AttachPanel(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            // 功能多的时候只长到这么高，剩下的在面板里滚 —— 键盘弹出来也压不到输入框。
            .heightIn(max = 176.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * 面板里的一格：**图标在上、字在下**，宽 72 × 高 64 ——
 * 视觉比原来那排横条小，点击区却更大（比 48dp 的触摸目标还宽松）。
 */
@Composable
internal fun AttachTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    Column(
        Modifier
            .width(72.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.content.copy(alpha = if (enabled) 0.08f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) palette.accentContent else palette.contentSecondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (enabled) palette.content else palette.contentSecondary,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/**
 * 输入栏上的圆形图标按钮：**视觉 34dp，点击区 44dp** ——
 * 看着小、够得着，输入框能多占一点地方。
 */
@Composable
internal fun BarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
    background: Color = Color.Transparent,
    /**
     * 按钮槽的高度。**和输入框那一行同高**时，单行状态下按钮和文字才会在同一条中心线上；
     * 多行时这一行是底对齐的，槽高固定，所以按钮不会跟着文字上下漂。
     */
    slotHeight: Dp = 44.dp,
) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier.height(slotHeight).width(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint ?: palette.accentContent, modifier = Modifier.size(20.dp))
        }
    }
}

/** 面板里的「按住说话」：和输入框上是同一套手势（[HoldToTalkLayer]），只是换了个样子。 */
@Composable
internal fun HoldTile(
    recording: Boolean,
    onTap: () -> Unit,
    onStart: () -> Boolean,
    onMove: (HoldTarget?) -> Unit,
    onEnd: (HoldTarget?) -> Unit,
) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .width(72.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (recording) palette.error.copy(alpha = 0.85f) else palette.content.copy(alpha = 0.08f)),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.Mic,
                contentDescription = null,
                tint = if (recording) Color.White else palette.accentContent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (recording) "松手发送" else "按住说话",
                color = if (recording) Color.White else palette.content,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        HoldToTalkLayer(
            onTap = onTap,
            onStart = onStart,
            onMove = onMove,
            onEnd = onEnd,
            modifier = Modifier.matchParentSize(),
        )
    }
}
