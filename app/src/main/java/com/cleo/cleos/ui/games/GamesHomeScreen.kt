package com.cleo.cleos.ui.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette

/** 小游戏中心：现在只有井字棋（P0）。围棋、五子棋、联机都还没做。 */
@Composable
fun GamesHomeScreen(onBack: () -> Unit, onOpenTicTacToe: () -> Unit) {
    val palette = LocalGlassPalette.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回", color = palette.accentContent) }
            Spacer(Modifier.weight(1f))
            Text("小游戏中心", color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
        }
        GlassSurface(
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "打开井字棋", onClick = onOpenTicTacToe),
            shape = GlassShape.Rounded(24.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SportsEsports, contentDescription = null, tint = palette.contentSecondary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("井字棋", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("邀请一个 TA 陪你下一局", color = palette.contentSecondary, fontSize = 12.sp)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.contentSecondary)
            }
        }
        Text(
            "现在只有井字棋；围棋、五子棋、多人联机还没做。TA 的落子现在是本地模拟（还没接模型）。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}
