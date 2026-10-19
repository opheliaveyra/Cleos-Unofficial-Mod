package com.cleo.cleos.ui.icons

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.cleo.cleos.BuildConfig

/**
 * Visual-only adapter. Material keeps ownership of layout and accessibility semantics.
 * Unmapped navigation/status glyphs retain their original vector AND tint. Red/green
 * semantic status tints also retain Material, so recording/error/success remain legible.
 * The mod build can opt out with -PpaperIcons=false; upstream is never modified.
 */
@Composable
fun PaperIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val semanticTint = tint != Color.Unspecified && (
        (tint.red > tint.green * 1.35f && tint.red > tint.blue * 1.15f) ||
        (tint.green > tint.red * 1.35f && tint.green > tint.blue * 1.15f)
    )
    val paper = if (BuildConfig.PAPER_ICONS_ENABLED && !semanticTint)
        PaperVectors.replacement(imageVector) else null
    if (paper == null) {
        Icon(imageVector, contentDescription, modifier, tint)
    } else {
        Icon(
            imageVector = paper,
            contentDescription = contentDescription,
            modifier = modifier.graphicsLayer { alpha = if (tint == Color.Unspecified) 1f else tint.alpha },
            tint = Color.Unspecified,
        )
    }
}
