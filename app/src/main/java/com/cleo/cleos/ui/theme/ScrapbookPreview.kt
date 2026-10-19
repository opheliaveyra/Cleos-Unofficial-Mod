package com.cleo.cleos.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cleo.cleos.data.VisualTheme
import com.cleo.cleos.glass.GlassTab
import com.cleo.cleos.glass.LocalGlassPalette

/** Real composables for Android Studio rendering; not an APK screenshot or device test. */
@Preview(name = "Scrapbook navigation 320dp", widthDp = 320, showBackground = true)
@Preview(name = "Scrapbook navigation 411dp", widthDp = 411, showBackground = true)
@Composable
private fun ScrapbookNavigationPreview() {
    var selected by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalGlassPalette provides scrapbookPalette()) {
        MaterialTheme {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                VisualThemePicker(VisualTheme.LAVENDER) { }
                ScrapbookTabBar(
                    listOf(GlassTab("聊天", Icons.Outlined.ChatBubbleOutline), GlassTab("日记", Icons.Outlined.AutoStories),
                        GlassTab("待办", Icons.Outlined.TaskAlt), GlassTab("主页", Icons.Outlined.Home)),
                    selected, { selected = it },
                )
            }
        }
    }
}
