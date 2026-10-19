package com.cleo.cleos.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import com.cleo.cleos.ui.theme.CleosIcon

private data class DiaryCard(
    val id: Long,
    val date: LocalDate,
    val title: String,
    val excerpt: String,
    val cover: File?,
    val imageCount: Int,
    /** The TA who wrote it; null for the person's own. */
    val byTa: Long?,
    val secret: Boolean,
)

private sealed interface DiaryRow {
    data class Month(val month: YearMonth) : DiaryRow
    data class Entry(val card: DiaryCard) : DiaryRow
}

/**
 * 日记类型：**只影响展示**，不改变任何数据的归属。
 * 和「看谁的书」（归属范围）是两件事，所以分成两排。
 */
private enum class DiaryType(val label: String, val empty: String, val hint: String) {
    All("全部", "还没有日记", "点右下角的笔，写第一篇"),
    Mine("我写的", "你还没写过日记", "点右下角的笔，写第一篇"),
    Theirs("TA 写的", "TA 还没写过日记", "在聊天里请 TA 写一篇"),
    Secrets("小秘密", "还没有小秘密", "你和 TA 都可以把想留给自己的话写在这里"),
    ;

    fun accepts(e: DiaryEntryEntity) = when (this) {
        All -> true
        Mine -> e.author == DiaryEntryEntity.AUTHOR_ME
        Theirs -> e.author == DiaryEntryEntity.AUTHOR_AI
        Secrets -> e.secret
    }
}

/** 归属范围：0 = 当前角色，-1 = 所有角色，>0 = 指定角色 id。 */
private const val SCOPE_ALL = -1L
private const val SCOPE_CURRENT = 0L

@Composable
fun DiaryTab(bottomInset: Dp, onOpenEntry: (id: Long, secret: Boolean) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    // 归属范围（看谁的书）与类型（看哪几篇）分开；两个都只影响展示，不改数据归属。
    var scope by rememberSaveable { mutableLongStateOf(SCOPE_CURRENT) }
    var type by rememberSaveable { mutableStateOf(DiaryType.All) }
    // 当前 TA：**默认就是它的那本书**。切角色时数据源跟着换，不用手动刷新。
    val ta by remember { c.companions.current }.collectAsStateWithLifecycle(null)
    val allTas by c.companions.all.collectAsStateWithLifecycle(emptyList())
    val scopeTa = allTas.firstOrNull { it.id == scope }
    val scopeLabel = when {
        scope == SCOPE_ALL -> "所有角色"
        scope == SCOPE_CURRENT -> ta?.name?.trim().orEmpty().ifEmpty { "当前角色" }
        else -> scopeTa?.name?.trim().orEmpty().ifEmpty { "角色" }
    }
    val rows by remember(scope, ta?.id, type) {
        val source = when {
            scope == SCOPE_ALL -> c.db.diary().observeAll()
            scope == SCOPE_CURRENT -> ta?.let { c.db.diary().observeFor(it.id) } ?: flowOf(emptyList())
            else -> c.db.diary().observeFor(scope)
        }
        source
            .map { entries -> toRows(entries.filter(type::accepts)) { c.images.file(it) } }
            .flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(null)
    // Which TA wrote which entry, by name: the book holds every TA's pages.
    val names by remember { c.companions.all.map { list -> list.associate { it.id to it.name.trim().ifEmpty { "TA" } } } }
        .collectAsStateWithLifecycle(emptyMap())
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val count = rows?.count { it is DiaryRow.Entry } ?: 0

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "日记",
                // 标题下面写清楚正在看谁的书（「全部」时才不写）。
                subtitle = listOfNotNull(scopeLabel, if (count > 0) "$count 篇" else null).joinToString(" · "),
                backdrop = page,
            )
            // Writing from the secrets page starts locked: that page is where secrets are kept.
            val secret = type == DiaryType.Secrets
            GlassIconButton(
                if (secret) Icons.Rounded.Lock else Icons.Rounded.EditNote,
                if (secret) "写小秘密" else "写日记",
                { onOpenEntry(0L, secret) },
                page,
                style = palette.accentSurface,
                tint = Color.White,
                size = 58.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 让开底部导航和列表最后一条。
                    .padding(end = 20.dp, bottom = bottomInset + 16.dp),
            )
        },
    ) {
        val list = rows
        if (list != null && list.isEmpty()) {
            GlassSurface(
                modifier = Modifier.align(Alignment.Center),
                style = palette.notice,
                shape = GlassShape.Rounded(22.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(type.empty, color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.size(6.dp))
                    Text(type.hint, color = palette.contentSecondary, fontSize = 14.sp)
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fadeUnderTopBar(statusTop + TopBarHeight, bottom = bottomInset - 10.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 6.dp, bottom = bottomInset + 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "filter") {
                DiaryFilters(
                    scope = scope,
                    scopeLabel = scopeLabel,
                    allTas = allTas,
                    currentTaId = ta?.id,
                    type = type,
                    onScope = { scope = it },
                    onType = { type = it },
                )
            }
            items(list.orEmpty(), key = {
                when (it) {
                    is DiaryRow.Month -> "m${it.month}"
                    is DiaryRow.Entry -> it.card.id
                }
            }) { row ->
                when (row) {
                    is DiaryRow.Month -> MonthHeader(row.month)
                    is DiaryRow.Entry -> DiaryCardView(
                        card = row.card,
                        author = when {
                            row.card.secret -> if (row.card.byTa != null) "${names[row.card.byTa] ?: "TA"}的小秘密" else "我的小秘密"
                            row.card.byTa != null -> "${names[row.card.byTa] ?: "TA"}写的"
                            else -> "我写的"
                        },
                    ) {
                        onOpenEntry(row.card.id, false)
                    }
                }
            }
        }
    }
}

private fun toRows(entries: List<DiaryEntryEntity>, file: (String) -> File): List<DiaryRow> {
    val rows = ArrayList<DiaryRow>(entries.size + 12)
    var month: YearMonth? = null
    for (e in entries) {
        val date = LocalDate.ofEpochDay(e.day)
        val ym = YearMonth.from(date)
        if (ym != month) {
            rows += DiaryRow.Month(ym)
            month = ym
        }
        val blocks = DiaryBlocks.decode(e.blocks)
        val images = DiaryBlocks.images(blocks)
        rows += DiaryRow.Entry(
            DiaryCard(
                id = e.id,
                date = date,
                title = if (e.lockedForUser) "TA 的小秘密" else e.title,
                excerpt = if (e.lockedForUser) e.publicHint.ifBlank { "有些话，暂时想留给自己" } else DiaryBlocks.plainText(blocks).replace(Regex("\\s+"), " ").take(160),
                cover = if (e.lockedForUser) null else images.firstOrNull()?.let { file(it.file) },
                imageCount = images.size,
                byTa = if (e.author == DiaryEntryEntity.AUTHOR_AI) e.companionId ?: 0L else null,
                secret = e.secret,
            ),
        )
    }
    return rows
}

@Composable
private fun MonthHeader(month: YearMonth) {
    val palette = LocalGlassPalette.current
    // 月份标题和卡片左边缘对齐（不再缩进）。
    Box(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        GlassSurface(style = palette.bar, shape = GlassShape.Capsule, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)) {
            Text(Dates.yearMonth(month.atDay(1)), color = palette.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 两排筛选：**上面一排是归属范围**（下拉：当前角色 / 别的角色 / 所有角色），
 * **下面一排是日记类型**（全部 / 我写的 / TA 写的 / 小秘密）。
 *
 * 类型那一排：单行、所有选项等高（固定 30dp）、文字不换行；放不下就横向滚，不压缩成竖排。
 * 底色的不透明度比默认玻璃高一点（card.tint 再压一层），壁纸花的时候字也清楚。
 */
@Composable
private fun DiaryFilters(
    scope: Long,
    scopeLabel: String,
    allTas: List<CompanionEntity>,
    currentTaId: Long?,
    type: DiaryType,
    onScope: (Long) -> Unit,
    onType: (DiaryType) -> Unit,
) {
    val palette = LocalGlassPalette.current
    var open by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 第一排：归属范围。
        Box {
            GlassSurface(style = palette.card, shape = GlassShape.Capsule, contentPadding = PaddingValues(0.dp)) {
                Row(
                    Modifier
                        .background(palette.card.tint.copy(alpha = 0.55f), CircleShape)
                        .clickable(interactionSource = null, indication = null) { open = true }
                        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        scopeLabel,
                        color = palette.content, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, softWrap = false,
                    )
                    CleosIcon(Icons.Rounded.ArrowDropDown, contentDescription = "换一个范围", tint = palette.contentSecondary, modifier = Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("当前角色") }, onClick = { open = false; onScope(SCOPE_CURRENT) })
                allTas.filter { it.id != currentTaId }.forEach { other ->
                    DropdownMenuItem(
                        text = { Text(other.name.trim().ifEmpty { "TA" }, maxLines = 1) },
                        onClick = { open = false; onScope(other.id) },
                    )
                }
                DropdownMenuItem(text = { Text("所有角色") }, onClick = { open = false; onScope(SCOPE_ALL) })
            }
        }
        // 第二排：日记类型。
        GlassSurface(style = palette.card, shape = GlassShape.Capsule, contentPadding = PaddingValues(4.dp)) {
            Row(
                Modifier
                    .horizontalScroll(scroll)
                    .background(palette.card.tint.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiaryType.entries.forEach { t ->
                    val on = t == type
                    Box(
                        Modifier
                            // 固定等高（不是被文字撑出来的），大字体下也不会把整排撑高。
                            .height(34.dp)
                            .background(if (on) palette.accent else Color.Transparent, CircleShape)
                            .clickable(interactionSource = null, indication = null) { onType(t) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            t.label,
                            color = if (on) Color.White else palette.content,
                            fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardTag(icon: ImageVector, text: String) {
    val palette = LocalGlassPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
        CleosIcon(icon, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = palette.accentContent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 统一卡片：左边固定宽度日期 + 星期；右边依次是 作者/类型、标题、最多两行摘要；
 * 有图时固定 64dp 缩略图，文字列 weight(1f) 保住空间。
 * 所有间距、内边距都固定，内容多少都不会让位置跳。
 */
@Composable
private fun DiaryCardView(card: DiaryCard, author: String, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onClick),
        style = palette.card,
        shape = GlassShape.Rounded(22.dp),
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 卡片底再压一层，壁纸再花也不至于把字吃掉。
                .background(palette.card.tint.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${card.date.dayOfMonth}",
                    color = palette.accentContent, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    lineHeight = 28.sp, maxLines = 1,
                )
                Text(
                    Dates.weekday(card.date).replace("星期", "周"),
                    color = palette.contentSecondary, fontSize = 11.sp, maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CardTag(
                    when {
                        card.secret -> Icons.Rounded.Lock
                        card.byTa != null -> Icons.Rounded.AutoAwesome
                        else -> Icons.Rounded.EditNote
                    },
                    author,
                )
                if (card.title.isNotBlank()) {
                    Text(
                        card.title,
                        color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    card.excerpt.isNotBlank() -> Text(
                        card.excerpt,
                        color = palette.content.copy(alpha = 0.82f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    card.title.isBlank() -> Text("${card.imageCount} 张图片", color = palette.contentSecondary, fontSize = 13.sp)
                }
            }
            card.cover?.let { cover ->
                Spacer(Modifier.width(10.dp))
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}
