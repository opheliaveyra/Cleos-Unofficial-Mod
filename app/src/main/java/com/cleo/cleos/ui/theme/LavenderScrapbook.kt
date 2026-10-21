package com.cleo.cleos.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.R
import com.cleo.cleos.data.VisualTheme
import com.cleo.cleos.glass.GlassPalettes
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassStyle
import com.cleo.cleos.glass.GlassTab
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val LocalVisualTheme = staticCompositionLocalOf { VisualTheme.NATIVE }

private val Paper = Color(0xFFFFFBF5)
private val Ink = Color(0xFF40364F)
private val Purple = Color(0xFF73538E)
private val Tape = Color(0xFFE5D9F1)
/** 选中的标签文字：比常态略深一档，不加阴影。 */
private val TapeInk = Color(0xFF3A2E4B)

internal fun scrapbookPalette() = GlassPalettes.build(dark = false).copy(
    content = Ink,
    contentSecondary = Color(0xFF655A70),
    accent = Purple,
    accentContent = Purple,
)

/**
 * 紫雾手账的固定背景：Aster 画的那张紫雾拼贴纸。
 *
 * 是主题内置资源 —— 不走设置里的壁纸，也不允许单独换；用户切到别的主题时
 * 才回到自己的壁纸（设置本身没被改动，切回来还是原样）。
 *
 * 适配：等比放大填满（Crop）。图是 852×1846，比例 0.4615，正好是常见手机的比例，
 * 所以不会拉伸；比它更宽/更窄的屏幕只在中缝处裁掉一点，四角的装饰保留。
 */
@Composable
internal fun ScrapbookBackground() {
    Image(
        painter = painterResource(R.drawable.scrap_bg_paper),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
}

/** Shared fallback material until page-specific paper pieces arrive in later batches. */
internal fun scrapbookSurface(shape: GlassShape, style: GlassStyle): Modifier = Modifier.drawBehind {
    val radius = shape.radiusPx(size, this)
    // Accent surfaces may carry white text; retain their saturated tint for contrast.
    val fill = if (style.tint.alpha >= 0.5f && style.tint.red < 0.7f) Purple else Paper
    drawRoundRect(fill, cornerRadius = CornerRadius(radius,radius))
    drawRoundRect(Color(0x3373538E), cornerRadius = CornerRadius(radius,radius), style = Stroke(0.7.dp.toPx()))
}

@Composable
internal fun VisualThemePicker(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(VisualTheme.NATIVE to "原版默认", VisualTheme.LAVENDER to "紫雾手账 · 试用").forEach { (id,label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = VisualTheme.normalize(selected) == id, role = Role.RadioButton, onClick = { onSelect(id) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (VisualTheme.normalize(selected) == id) "●" else "○", color = Purple)
                Spacer(Modifier.width(12.dp))
                Text(label, color = com.cleo.cleos.glass.LocalGlassPalette.current.content)
            }
        }
    }
}

/** Same outer height as glass navigation, so existing insets/IME visibility stay owned by MainScreen. */
@Composable
internal fun ScrapbookTabBar(tabs: List<GlassTab>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    // 胶带是可以揭起来换位置的：长按选中的那格 -> 揭起 -> 跟着手指走 -> 松手贴到目标上。
    // 位置全部按导航栏的实际宽度算，不写死坐标。
    var selected by remember { mutableStateOf(selectedIndex) }
    if (selected != selectedIndex) selected = selectedIndex
    // 胶带相对「当前选中格子中心」的水平偏移（px）。拖动时跟手，松手时归零或跳到新格子。
    val tapeShift = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var hover by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val lift by animateFloatAsState(if (dragging) 1f else 0f, tween(180), label = "lift")
    val density = LocalDensity.current
    val tapeW = 52.dp
    val tabCount = tabs.size.coerceAtLeast(1)

    BoxWithConstraints(modifier.height(92.dp), contentAlignment = Alignment.Center) {
        val tabWidthPx = with(density) { maxWidth.toPx() } / tabCount
        val tapeWidthPx = with(density) { tapeW.toPx() }
        val rowHeightPx = with(density) { 64.dp.toPx() }

        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                // 手势挂在这一层：子元素的 selectable 在它里面，
                // 轻点先给子元素处理（正常切页），长按住才会轮到这里的拖动检测。
                // 之前这段挂在一个盖住整条导航栏的 Box 上，把轻点整个吞掉了。
                .pointerInput(tabCount, selected, tabWidthPx) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            val idx = (start.x / tabWidthPx).toInt().coerceIn(0, tabCount - 1)
                            if (idx == selected) { dragging = true; hover = selected }
                        },
                        onDrag = { change, delta ->
                            if (!dragging) return@detectDragGesturesAfterLongPress
                            change.consume()
                            scope.launch {
                                val next = tapeShift.value + delta.x
                                val minShift = -(selected + 0.5f) * tabWidthPx
                                val maxShift = (tabCount - selected - 0.5f) * tabWidthPx
                                tapeShift.snapTo(next.coerceIn(minShift, maxShift))
                            }
                            val c = (selected + 0.5f) * tabWidthPx + tapeShift.value
                            hover = (c / tabWidthPx).toInt().coerceIn(0, tabCount - 1)
                        },
                        onDragEnd = {
                            if (!dragging) return@detectDragGesturesAfterLongPress
                            val target = hover.coerceIn(0, tabCount - 1)
                            val wantShift = (target - selected) * tabWidthPx
                            dragging = false; hover = -1
                            scope.launch {
                                tapeShift.animateTo(wantShift, tween(170))
                                if (target != selected) { selected = target; onSelect(target) }
                                tapeShift.snapTo(0f)
                            }
                        },
                        onDragCancel = {
                            if (!dragging) return@detectDragGesturesAfterLongPress
                            dragging = false; hover = -1
                            scope.launch { tapeShift.animateTo(0f, tween(170)) }
                        },
                    )
                }.drawBehind {
                val w = size.width
                val h = size.height
                val e = 2.dp.toPx()
                val top = h * .45f
                val bottom = top + 6.dp.toPx()
                val p = Path().apply {
                    moveTo(0f,top)
                    cubicTo(w*.18f,top-e*2,w*.32f,top+e*2,w*.5f,top)
                    cubicTo(w*.68f,top-e*2,w*.85f,top+e*2,w,top)
                    lineTo(w,bottom)
                    cubicTo(w*.8f,bottom+e*2,w*.66f,bottom-e*2,w*.5f,bottom)
                    cubicTo(w*.33f,bottom+e*2,w*.16f,bottom-e*2,0f,bottom)
                    close()
                }
                drawPath(p,Paper)
                drawPath(p,Color(0x5573538E),style=Stroke(0.8.dp.toPx()))
            }.padding(horizontal=4.dp,vertical=4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index,tab ->
                val active = selected == index
                val targeted = dragging && hover == index
                Column(
                    Modifier.weight(1f).heightIn(min=56.dp)
                        .selectable(selected=active,role=Role.Tab,onClick={ onSelect(index) })
                        .padding(vertical=5.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(2.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CleosIcon(
                            imageVector = if (active) tab.selectedIcon else tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(if (active) 31.dp else 28.dp),
                            // 拖动经过时给一点反馈：图标变大一点、颜色靠近紫色，但不提前切页。
                            tint = if (active || targeted) Purple else Ink,
                        )
                        // 胶带就画在图标这一格里 —— 静止位置本来就是对的，
                        // 拖动时只叠一个 translationX，比重新算绝对坐标可靠得多。
                        if (active) {
                            Box(
                                Modifier
                                    .offset(y = 1.dp)
                                    .width(52.dp)
                                    .height(36.dp)
                                    .graphicsLayer {
                                        translationX = if (dragging) tapeShift.value else 0f
                                        scaleX = 1f + lift * 0.06f
                                        scaleY = 1f + lift * 0.06f
                                        rotationZ = lift * -2.5f
                                    }
                                    .drawBehind { drawTornTape(size) },
                            )
                        }
                    }
                    Text(
                        tab.label,
                        color = if (active) TapeInk else Ink,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

    }
}
/**
 * 手撕和纸胶带：横向矩形 + 两端细密小三角锯齿。
 *
 * 锯齿**向内凹**（不超出这块 Box），所以两端不会盖住旁边的入口。
 * 透明度约 26%：图标透得出来，又能看出胶带在。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTornTape(size: androidx.compose.ui.geometry.Size) {
    val tooth = 2.6.dp.toPx()
    val depth = 2.4.dp.toPx()
    val l = 0f
    val r = size.width
    val t = 0f
    val b = size.height
    val path = Path()

    // 左上起笔，左边缘自上而下走锯齿
    path.moveTo(l + depth, t)
    var y = t
    var inward = true
    while (y < b) {
        y = kotlin.math.min(y + tooth, b)
        path.lineTo(if (inward) l + depth else l, y)
        inward = !inward
    }
    // 底边
    path.lineTo(r - depth, b)
    // 右边缘自下而上走锯齿
    var yy = b
    inward = true
    while (yy > t) {
        yy = kotlin.math.max(yy - tooth, t)
        path.lineTo(if (inward) r - depth else r, yy)
        inward = !inward
    }
    // 顶边回到起点
    path.lineTo(l + depth, t)
    path.close()

    // 浅紫和纸：26% 不透明度，图标透得出来，但胶带边缘看得出来。
    drawPath(path, Color(0xFFA78FD0).copy(alpha = 0.46f))
}
