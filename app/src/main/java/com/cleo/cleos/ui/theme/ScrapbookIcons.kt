package com.cleo.cleos.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cleo.cleos.R
import com.cleo.cleos.data.VisualTheme
import com.cleo.cleos.ui.theme.CleosIcon

/**
 * 拼贴手账图标接入层。
 *
 * 只在 [VisualTheme.LAVENDER] 下把语义匹配的原生 Material 图标换成已切割的拼贴素材；
 * 其它情况（含原作者默认主题）一律走原来的 Material 图标，行为完全不变。
 *
 * 映射以 [ImageVector.name] 为键，因为 Material 图标无论被多少次引用都是同一对象名，
 * 而调用点不需要知道素材存在与否 —— 没有素材的语义自动回退原生图标。
 */
internal object ScrapbookIconMap {

    /** ImageVector.name -> drawable. 只登记语义真正对得上的，宁缺毋滥。 */
    val byVectorName: Map<String, Int> = buildMap {
        fun put(name: String, ids: IntArray) = ids.forEach { id ->
            // Material 三种风格都登记，调用点用哪个风格都能命中
            put("Filled.$name", id)
            put("Outlined.$name", id)
            put("Rounded.$name", id)
            put("AutoMirrored.Filled.$name", id)
            put("AutoMirrored.Outlined.$name", id)
        }

        put("Home", intArrayOf(R.drawable.scrap_home))
        put("ChatBubble", intArrayOf(R.drawable.scrap_chat))
        put("ChatBubbleOutline", intArrayOf(R.drawable.scrap_chat))
        put("Forum", intArrayOf(R.drawable.scrap_chat))
        put("AddComment", intArrayOf(R.drawable.scrap_chat))
        put("AutoStories", intArrayOf(R.drawable.scrap_diary))
        put("Book", intArrayOf(R.drawable.scrap_world))
        put("MenuBook", intArrayOf(R.drawable.scrap_world))
        put("TaskAlt", intArrayOf(R.drawable.scrap_todo))
        put("Checklist", intArrayOf(R.drawable.scrap_todo))
        put("Groups", intArrayOf(R.drawable.scrap_role))
        put("Person", intArrayOf(R.drawable.scrap_role))
        put("PersonAdd", intArrayOf(R.drawable.scrap_role))
        put("Face", intArrayOf(R.drawable.scrap_role))
        put("Folder", intArrayOf(R.drawable.scrap_file))
        put("Inventory2", intArrayOf(R.drawable.scrap_file))
        put("Image", intArrayOf(R.drawable.scrap_gallery))
        put("AddPhotoAlternate", intArrayOf(R.drawable.scrap_gallery))
        put("VideoLibrary", intArrayOf(R.drawable.scrap_gallery))
        put("PhotoCamera", intArrayOf(R.drawable.scrap_camera))
        put("MusicNote", intArrayOf(R.drawable.scrap_music))
        put("SportsEsports", intArrayOf(R.drawable.scrap_game))
        put("Extension", intArrayOf(R.drawable.scrap_settings))
        put("TravelExplore", intArrayOf(R.drawable.scrap_search))
        put("Explore", intArrayOf(R.drawable.scrap_map))
        put("LocationOn", intArrayOf(R.drawable.scrap_map))
        put("CalendarMonth", intArrayOf(R.drawable.scrap_calendar))
        put("Event", intArrayOf(R.drawable.scrap_calendar))
        put("Description", intArrayOf(R.drawable.scrap_note))
        put("EditNote", intArrayOf(R.drawable.scrap_note))
        put("Edit", intArrayOf(R.drawable.scrap_note))
        put("Favorite", intArrayOf(R.drawable.scrap_favorite))
        put("FavoriteBorder", intArrayOf(R.drawable.scrap_favorite))
        put("Mail", intArrayOf(R.drawable.scrap_mail))
        put("MailOutline", intArrayOf(R.drawable.scrap_mail))
        put("MarkEmailUnread", intArrayOf(R.drawable.scrap_mail))
        put("MarkChatRead", intArrayOf(R.drawable.scrap_mail))
        put("AlternateEmail", intArrayOf(R.drawable.scrap_mail))
        put("Memory", intArrayOf(R.drawable.scrap_ai))
        put("Psychology", intArrayOf(R.drawable.scrap_ai))
        put("Settings", intArrayOf(R.drawable.scrap_settings))
        put("Cloud", intArrayOf(R.drawable.scrap_sync))
        put("Alarm", intArrayOf(R.drawable.scrap_clock))
        put("Timer", intArrayOf(R.drawable.scrap_clock))
        put("Bookmark", intArrayOf(R.drawable.scrap_tag))
        put("BookmarkBorder", intArrayOf(R.drawable.scrap_tag))
        put("PushPin", intArrayOf(R.drawable.scrap_tag))
        put("DeleteOutline", intArrayOf(R.drawable.scrap_delete))
        put("Search", intArrayOf(R.drawable.scrap_search))
        put("Notifications", intArrayOf(R.drawable.scrap_notify))
        put("NotificationsNone", intArrayOf(R.drawable.scrap_notify))
        put("VolumeOff", intArrayOf(R.drawable.scrap_mute))
        put("Mic", intArrayOf(R.drawable.scrap_voice))
        put("MicOff", intArrayOf(R.drawable.scrap_voice))
        put("GraphicEq", intArrayOf(R.drawable.scrap_voice))
        put("AttachFile", intArrayOf(R.drawable.scrap_attach))
        put("AutoAwesome", intArrayOf(R.drawable.scrap_star))
        put("Star", intArrayOf(R.drawable.scrap_star))
        put("HeartBroken", intArrayOf(R.drawable.scrap_heart))
        put("Add", intArrayOf(R.drawable.scrap_add))
        put("MoreHoriz", intArrayOf(R.drawable.scrap_more))
        put("MoreVert", intArrayOf(R.drawable.scrap_more_actions))
        put("ArrowBack", intArrayOf(R.drawable.scrap_back))
        put("KeyboardArrowRight", intArrayOf(R.drawable.scrap_forward))
        put("PlayArrow", intArrayOf(R.drawable.scrap_play))
        put("PlayCircle", intArrayOf(R.drawable.scrap_play))
        put("Pause", intArrayOf(R.drawable.scrap_pause))
        put("VolumeUp", intArrayOf(R.drawable.scrap_volume))
        put("Refresh", intArrayOf(R.drawable.scrap_refresh))
        put("Close", intArrayOf(R.drawable.scrap_close))
        // 注意：不要给 "Check" 映射 scrap_check。
        // 那张素材是早期画的，勾的形状不清晰（看起来像水滴），而且底圈和花占了太多空间，
        // 缩小到菜单里的 20dp 根本认不出是「对勾」。对勾是纯功能性标记，必须一眼可读，
        // 所以这里回落到 CleosIcon 的默认勾，不套纸绘。
        put("Info", intArrayOf(R.drawable.scrap_info))
        put("Warning", intArrayOf(R.drawable.scrap_warning))
        put("WarningAmber", intArrayOf(R.drawable.scrap_warning))

        // —— 第二批（2026-10-20 她补发的 16 张）——
        put("Send", intArrayOf(R.drawable.scrap_send))
        put("Palette", intArrayOf(R.drawable.scrap_palette))
        put("GridOn", intArrayOf(R.drawable.scrap_grid))
        put("Apps", intArrayOf(R.drawable.scrap_grid))
        put("Lock", intArrayOf(R.drawable.scrap_lock))
        put("LockOpen", intArrayOf(R.drawable.scrap_lock_open))
        put("DynamicFeed", intArrayOf(R.drawable.scrap_dynamic_feed))
        put("PhotoLibrary", intArrayOf(R.drawable.scrap_dynamic_feed))
        put("Call", intArrayOf(R.drawable.scrap_call))
        put("Phone", intArrayOf(R.drawable.scrap_call))
        put("CallEnd", intArrayOf(R.drawable.scrap_call_end))
        put("ContentCopy", intArrayOf(R.drawable.scrap_copy))
        put("TextFields", intArrayOf(R.drawable.scrap_text_fields))
        put("EmojiEmotions", intArrayOf(R.drawable.scrap_emoji))
        put("Mood", intArrayOf(R.drawable.scrap_emoji))
        put("FileDownload", intArrayOf(R.drawable.scrap_download))
        put("Download", intArrayOf(R.drawable.scrap_download))
        put("Key", intArrayOf(R.drawable.scrap_key))
        put("Lightbulb", intArrayOf(R.drawable.scrap_lightbulb))
        put("Widgets", intArrayOf(R.drawable.scrap_widgets))
        put("SkipNext", intArrayOf(R.drawable.scrap_skip_next))
        put("Reply", intArrayOf(R.drawable.scrap_bubble_reply))
    }

    /** 没有素材、必须回退原生的语义，记录在案便于出缺失清单。 */
    val knownMissing: Set<String> = setOf(
        "ArrowUpward", "ArrowDownward", "ArrowDropDown", "ExpandLess", "ExpandMore",
        "Stop",
    )
}

/**
 * 主题感知图标。签名对齐 [Icon] 的常用重载，调用点把 `CleosIcon(Icons.X, ...` 换成
 * `CleosCleosIcon(Icons.X, ...` 即可；lavender 之外行为与之前逐字节相同。
 */
@Composable
internal fun CleosIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val scrap = if (LocalVisualTheme.current == VisualTheme.LAVENDER) {
        ScrapbookIconMap.byVectorName[imageVector.name]
    } else null
    if (scrap != null) {
        // 拼贴素材自带纸色与花/丝带，染色会毁掉风格，所以这里不套 tint。
        // 尺寸按调用点的 modifier 走；素材本身带贴纸留白，视觉上会比同尺寸矢量略小。
        Image(
            painter = painterResource(scrap),
            contentDescription = contentDescription,
            modifier = modifier.graphicsLayer { alpha = if (tint == Color.Unspecified) 1f else tint.alpha },
        )
    } else {
        Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}

/** 带尺寸的便捷重载，供导航之类固定尺寸处使用。 */
@Composable
internal fun CleosIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    size: Dp,
    tint: Color = LocalContentColor.current,
) = CleosIcon(imageVector, contentDescription, Modifier.size(size), tint)

/** Shared decision for icon containers; native and missing glyphs keep their original chrome. */
@Composable
internal fun usesScrapbookArtwork(icon: ImageVector): Boolean =
    LocalVisualTheme.current == VisualTheme.LAVENDER && ScrapbookIconMap.byVectorName.containsKey(icon.name)
