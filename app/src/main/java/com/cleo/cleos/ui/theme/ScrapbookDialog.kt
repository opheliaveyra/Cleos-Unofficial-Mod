package com.cleo.cleos.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cleo.cleos.R
import com.cleo.cleos.data.VisualTheme

/**
 * 紫雾手账的弹窗纸框：Aster 画的柔边信纸（`frame_soft_note_lavender`）。
 *
 * **九宫格绘制**，不是整体拉伸：四角（胶带、花、右下角星）按原样画，
 * 四条边沿一个方向拉伸（线条粗细不变），中间纸面双向拉伸。
 * 所以弹窗可以随内容变高变宽，角落的装饰不会变形。
 *
 * 这个组件只负责「纸框 + 内容安全边距」；标题、列表、按钮、关闭行为仍由调用方决定。
 */
@Composable
internal fun ScrapbookDialogFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember {
        runCatching {
            val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.scrap_dialog_soft_note)
            (d as? android.graphics.drawable.BitmapDrawable)?.bitmap?.asImageBitmap()
        }.getOrNull()
    }
    Box(modifier) {
        if (bitmap != null) {
            Canvas(Modifier.matchParentSize()) {
                // 素材纸框四周留了一圈半透明边（约 4%），直接铺会被内容顶到纸面外。
                // 这里把框往外扩 10% 再画：纸面覆盖到 Box 边缘，四角的胶带/花仍然在可见范围内。
                // 素材纸框四周约有 3% 的半透明留白；扩 4.5% 刚好盖住它，
                // 再大就会把四角装饰推到内容外面（「关上」看着会贴到框外）。
                val grow = 0.045f
                translate(left = -size.width * grow, top = -size.height * grow) {
                    drawNine(bitmap, Size(size.width * (1 + grow * 2), size.height * (1 + grow * 2)))
                }
            }
        }
        // 内容往内收：避开四角的胶带/花和四边的双线框。
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(start = 34.dp, end = 34.dp, top = 76.dp, bottom = 26.dp),
            content = content,
        )
    }
}

/** 四角占边长的比例：装饰都在角落约 22% 里，剩下的中间可以自由拉伸。 */
private const val CORNER = 0.22f

/**
 * 按九宫格把 [src] 画满 [dst]。
 *
 * 九块：四角原尺寸、上下边横向拉、左右边纵向拉、中心双向拉。
 * 取整用 IntOffset/IntSize，避免相邻块之间出现 1px 缝。
 */
private fun DrawScope.drawNine(src: ImageBitmap, dst: Size) {
    val sw = src.width
    val sh = src.height
    // 整数边界：相邻块**共享同一条边界**，既不留缝也不重叠。
    val cx = Math.round(sw * CORNER).coerceIn(1, sw / 2 - 1)
    val cy = Math.round(sh * CORNER).coerceIn(1, sh / 2 - 1)
    val sx = intArrayOf(0, cx, sw - cx, sw)
    val sy = intArrayOf(0, cy, sh - cy, sh)

    val k = minOf(dst.width / sw, dst.height / sh)
    val dw = Math.round(dst.width)
    val dh = Math.round(dst.height)
    val dcx = Math.round(cx * k).coerceIn(1, dw / 2 - 1)
    val dcy = Math.round(cy * k).coerceIn(1, dh / 2 - 1)
    val dx = intArrayOf(0, dcx, dw - dcx, dw)
    val dy = intArrayOf(0, dcy, dh - dcy, dh)

    for (row in 0..2) {
        for (col in 0..2) {
            // **向外多取 1px**：拉伸过的块边缘是插值出来的半透明像素，
            // 正好落在分片线上就会显成一条浅色接缝。多盖 1px 把它藏到邻块底下。
            val so = IntOffset((sx[col] - if (col > 0) 1 else 0).coerceAtLeast(0), (sy[row] - if (row > 0) 1 else 0).coerceAtLeast(0))
            val ss = IntSize(
                (sx[col + 1] - so.x + if (col < 2) 1 else 0).coerceIn(1, sw - so.x),
                (sy[row + 1] - so.y + if (row < 2) 1 else 0).coerceIn(1, sh - so.y),
            )
            val dox = (dx[col] - if (col > 0) 1 else 0).coerceAtLeast(0)
            val doy = (dy[row] - if (row > 0) 1 else 0).coerceAtLeast(0)
            val ds = IntSize(
                (dx[col + 1] - dox + if (col < 2) 1 else 0).coerceIn(1, dw - dox),
                (dy[row + 1] - doy + if (row < 2) 1 else 0).coerceIn(1, dh - doy),
            )
            drawImage(
                image = src,
                srcOffset = so,
                srcSize = ss,
                dstOffset = IntOffset(dox, doy),
                dstSize = ds,
                filterQuality = FilterQuality.High,
            )
        }
    }
}

/** 这个主题要不要用纸绘弹窗；原生主题继续走系统 AlertDialog。 */
@Composable
internal fun usesScrapbookDialog(): Boolean = LocalVisualTheme.current == VisualTheme.LAVENDER
