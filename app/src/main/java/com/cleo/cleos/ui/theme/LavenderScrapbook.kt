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

val LocalVisualTheme = staticCompositionLocalOf { VisualTheme.NATIVE }

private val Paper = Color(0xFFFFFBF5)
private val Ink = Color(0xFF40364F)
private val Purple = Color(0xFF73538E)
private val Tape = Color(0xFFE5D9F1)

internal fun scrapbookPalette() = GlassPalettes.build(dark = false).copy(
    content = Ink,
    contentSecondary = Color(0xFF655A70),
    accent = Purple,
    accentContent = Purple,
)

@Composable
internal fun ScrapbookBackground() {
    Box(Modifier.fillMaxSize().background(Color(0xFFF3EDF7)).drawBehind {
        // Sparse fibers, no blur, no wallpaper mutation and no decoration over text.
        val step = 36.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(Color(0x0A73538E), Offset(0f,y), Offset(size.width,y+2.dp.toPx()), 0.5.dp.toPx())
            y += step
        }
    })
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
    Box(modifier.height(92.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).drawBehind {
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
                val active = selectedIndex == index
                Column(
                    Modifier.weight(1f).heightIn(min=56.dp)
                        .selectable(selected=active,role=Role.Tab,onClick={onSelect(index)})
                        .drawBehind {
                            if (active) drawRoundRect(Tape,topLeft=Offset(size.width*.16f,5.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width*.68f,34.dp.toPx()),cornerRadius=CornerRadius(2.dp.toPx()))
                        }.padding(vertical=5.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(2.dp),
                ) {
                    CleosIcon(
                        imageVector = if (active) tab.selectedIcon else tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(if (active) 31.dp else 28.dp),
                        tint = if (active) Purple else Ink,
                    )
                    Text(tab.label,color=Ink,fontSize=11.sp,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
