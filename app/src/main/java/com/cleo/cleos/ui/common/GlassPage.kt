package com.cleo.cleos.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.LocalGlassChrome
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.WallpaperOverscan
import com.cleo.cleos.glass.backdropSource
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.glass.rememberBackdrop

/** Height the floating top bar takes below the status bar; scrolling content pads by this. */
val TopBarHeight = 64.dp

/**
 * The shape of every screen: [body] scrolls underneath, [overlay] floats on top as glass.
 *
 * The body is recorded (over a copy of the wallpaper that only the recording gets) so the
 * overlay's glass can refract what scrolls under it. Glass inside the body can only look
 * at the plain wallpaper (LocalWallpaperBackdrop), because the body cannot contain glass
 * reading itself.
 */
@Composable
fun GlassPage(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(Backdrop) -> Unit = {},
    body: @Composable BoxScope.() -> Unit,
) {
    val page = rememberBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .backdropSource(page, behind = LocalWallpaperBackdrop.current, overscan = WallpaperOverscan),
            content = body,
        )
        overlay(page)
    }
}

/**
 * Glass buttons on either side, the title in a glass capsule in the middle.
 * Buttons are passed in as slots so each screen picks its own.
 */
@Composable
fun GlassTopBar(
    title: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    /** Makes the title a button; [titleMenu] is drawn next to it, e.g. a menu it opens. */
    onTitleClick: (() -> Unit)? = null,
    titleMenu: @Composable () -> Unit = {},
    /**
     * 副标题（模型名那一行）点一下干什么。
     *
     * 单独给副标题一个点击区，是因为它和标题是**两件事**：标题是"跟谁聊"（切 TA），
     * 副标题是"用哪个脑子"（切模型）。原来副标题后面画了个 ▾ 却点不动，
     * 那个箭头等于骗人。
     */
    onSubtitleClick: (() -> Unit)? = null,
    subtitleMenu: @Composable () -> Unit = {},
) {
    val palette = LocalGlassPalette.current
    CompositionLocalProvider(LocalGlassChrome provides palette.topBar) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = leading)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            // 紫雾手账：**所有页面**的标题都换成纸签。
            // 四个主页面有现成素材（title_home/diary/todo/chat）就直接用；
            // 其他页面（设置、对话记录、朋友圈、话题…）没有对应素材，用同一套纸面样式代码画，
            // 保证整站是同一套纸质感，而不是一半纸签一半玻璃胶囊。
            val lavenderTop = com.cleo.cleos.ui.theme.LocalVisualTheme.current == com.cleo.cleos.data.VisualTheme.LAVENDER
            val paperTitle = if (lavenderTop) when (title) {
                "主页" -> com.cleo.cleos.R.drawable.scrap_v1_title_home
                "日记" -> com.cleo.cleos.R.drawable.scrap_v1_title_diary
                "待办" -> com.cleo.cleos.R.drawable.scrap_v1_title_todo
                "聊天" -> com.cleo.cleos.R.drawable.scrap_v1_title_chat
                else -> null
            } else null
            if (lavenderTop && paperTitle != null) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(paperTitle),
                        contentDescription = title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .height(46.dp)
                            .then(
                                if (onTitleClick == null) Modifier
                                else Modifier.clickable(interactionSource = null, indication = null, onClick = onTitleClick),
                            ),
                    )
                    // 副标题必须留着 —— 聊天页的**模型切换就是这个位置**。
                    // 我上一版换纸签时只画了标题图，把这一行整个丢了，所以模型菜单点不开了。
                    // 这里补回来：副标题 + ▾，点了就开模型列表。
                    if (subtitle != null) {
                        Box(
                            Modifier
                                .padding(top = 2.dp)
                                .then(
                                    if (onSubtitleClick == null) Modifier
                                    else Modifier
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                        .clickable(interactionSource = null, indication = null, onClick = onSubtitleClick)
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                ),
                        ) {
                            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    subtitle,
                                    color = palette.contentSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (onSubtitleClick != null) {
                                    Text(" ▾", color = palette.contentSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            } else if (lavenderTop) {
                androidx.compose.foundation.layout.Column {
                    Box(
                        Modifier
                            .then(com.cleo.cleos.ui.theme.scrapbookSurface(GlassShape.Capsule, palette.topBarTitle))
                            .then(
                                if (onTitleClick == null) Modifier
                                else Modifier.clickable(interactionSource = null, indication = null, onClick = onTitleClick),
                            )
                            .padding(horizontal = 18.dp, vertical = if (subtitle == null) 10.dp else 6.dp),
                    ) {
                        Text(title, color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (subtitle != null) {
                        Text(subtitle, color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else Box(
                Modifier
                    .liquidGlass(backdrop, palette.topBarTitle, GlassShape.Capsule)
                    .then(
                        if (onTitleClick == null) Modifier else Modifier.clickable(interactionSource = null, indication = null, onClick = onTitleClick),
                    )
                    .padding(horizontal = 18.dp, vertical = if (subtitle == null) 10.dp else 6.dp),
            ) {
                androidx.compose.foundation.layout.Column {
                    Text(
                        title,
                        color = palette.content,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            color = palette.contentSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onSubtitleClick == null) Modifier else Modifier.clickable(
                                interactionSource = null,
                                indication = null,
                                onClick = onSubtitleClick,
                            ),
                        )
                    }
                }
                titleMenu()
                subtitleMenu()
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
    }
}
