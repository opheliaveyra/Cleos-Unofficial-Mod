package com.cleo.cleos.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 紫雾手账的撕纸气泡：直接用第二批切好的气泡素材做背景。
 *
 * 素材是固定长度的长条，气泡宽窄不定，所以按九宫格画（四角不动、边缘和中心拉伸），
 * 两端的花和丝带不会被拉变形。只在 lavender 主题下使用，原版气泡走 [ChatBubbleSurface] 原逻辑。
 *
 * 四角保留多少：横向各留 46dp（丝带+花都在这段里），纵向各留 14dp。
 */
private val CornerX = 46.dp
private val CornerY = 14.dp

@Composable
internal fun ScrapBubble(
    res: Int,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember(res) {
        runCatching {
            val d = androidx.core.content.ContextCompat.getDrawable(context, res)
            (d as? android.graphics.drawable.BitmapDrawable)?.bitmap?.asImageBitmap()
        }.getOrNull()
    }
    Box(modifier) {
        if (bitmap != null) {
            Canvas(Modifier.matchParentSize()) { drawNinePatch(bitmap, size) }
        }
        Box(Modifier.padding(padding)) { content() }
    }
}

/** 按九宫格把 [src] 画满 [dst]：四角原样、边和中间拉伸。 */
private fun DrawScope.drawNinePatch(src: ImageBitmap, dst: Size) {
    val sx = CornerX.toPx().coerceAtMost(src.width / 3f)
    val sy = CornerY.toPx().coerceAtMost(src.height / 3f)
    val sw = src.width.toFloat()
    val sh = src.height.toFloat()

    // 源图切三段：左/中/右、上/中/下
    val srcX = floatArrayOf(0f, sx, sw - sx)
    val srcW = floatArrayOf(sx, sw - sx * 2, sx)
    val srcY = floatArrayOf(0f, sy, sh - sy)
    val srcH = floatArrayOf(sy, sh - sy * 2, sy)

    // 目标图同样切三段
    val dx = floatArrayOf(0f, sx.coerceAtMost(dst.width / 3f), dst.width - sx.coerceAtMost(dst.width / 3f))
    val dwSeg = floatArrayOf(dx[1], (dst.width - dx[1] * 2).coerceAtLeast(1f), dx[1])
    val dy = floatArrayOf(0f, sy.coerceAtMost(dst.height / 3f), dst.height - sy.coerceAtMost(dst.height / 3f))
    val dhSeg = floatArrayOf(dy[1], (dst.height - dy[1] * 2).coerceAtLeast(1f), dy[1])

    for (row in 0..2) {
        for (col in 0..2) {
            if (dwSeg[col] <= 0f || dhSeg[row] <= 0f) continue
            drawImage(
                image = src,
                srcOffset = IntOffset(srcX[col].toInt(), srcY[row].toInt()),
                srcSize = IntSize(srcW[col].toInt().coerceAtLeast(1), srcH[row].toInt().coerceAtLeast(1)),
                dstOffset = IntOffset(dx[col].toInt(), dy[row].toInt()),
                dstSize = IntSize(dwSeg[col].toInt().coerceAtLeast(1), dhSeg[row].toInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.High,
            )
        }
    }
}

/** 气泡素材 ↔ 语义。没有对应素材的场景会退回接收/发送那张。 */
internal object ScrapBubbleRes {
    fun forMessage(mine: Boolean): Int =
        if (mine) com.cleo.cleos.R.drawable.scrap_v1_bubble_self_blank else com.cleo.cleos.R.drawable.scrap_v1_bubble_ta_blank

    val typing = com.cleo.cleos.R.drawable.scrap_bubble_typing
    val voice = com.cleo.cleos.R.drawable.scrap_bubble_voice
    val image = com.cleo.cleos.R.drawable.scrap_bubble_image
    val file = com.cleo.cleos.R.drawable.scrap_bubble_file
    val reply = com.cleo.cleos.R.drawable.scrap_bubble_reply
    val system = com.cleo.cleos.R.drawable.scrap_bubble_system
}
