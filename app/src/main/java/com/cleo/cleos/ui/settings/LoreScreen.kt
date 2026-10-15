package com.cleo.cleos.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.ImportException
import com.cleo.cleos.data.LoreKeys
import com.cleo.cleos.data.LoreSelection
import com.cleo.cleos.data.db.LoreEntity
import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

/**
 * A TA's 设定: the entries of a world book, or of a few of them, brought over from another app
 * or written here. They are not in front of the model with every message — the TA looks one up
 * with the lore tool when the talk turns to it (ai/Lore.kt), which is what keeps a long book
 * from filling every prompt.
 */
@Composable
fun LoreScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val ta by remember { c.companions.current }.collectAsStateWithLifecycle(null)
    val observedEntries by remember(ta?.id) { ta?.let { c.db.lore().observeFor(it.id) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    // A changed Flow may briefly retain its previous emission while the new TA loads.
    val entries = remember(observedEntries, ta?.id) { observedEntries.filter { it.companionId == ta?.id } }
    val name = ta?.name?.trim()?.ifEmpty { null } ?: "TA"
    var query by remember(ta?.id) { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var selecting by remember(ta?.id) { mutableStateOf(false) }
    var selected by remember(ta?.id) { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember(ta?.id) { mutableStateOf<Set<Long>?>(null) }
    var deleting by remember(ta?.id) { mutableStateOf(false) }
    fun cancelSelection() {
        if (!deleting) {
            selecting = false
            selected = emptySet()
            confirmDelete = null
        }
    }
    BackHandler(enabled = selecting) { cancelSelection() }
    LaunchedEffect(entries) {
        selected = selected intersect entries.mapTo(HashSet()) { it.id }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = ta?.id
        if (uri != null && id != null) {
            importing = true
            note = null
            scope.launch {
                note = try {
                    // The same door as 设置 › 数据与备份: a card here makes a TA and switches to them,
                    // and this screen then follows that TA.
                    c.imports.import(uri, id).said()
                } catch (e: ImportException) {
                    e.message
                } catch (e: Exception) {
                    "出错了：${e.message ?: e.javaClass.simpleName}"
                }
                importing = false
            }
        }
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val book = query.trim()
    val shown = remember(entries, book) { if (book.isEmpty()) {
        entries
    } else {
        entries.filter { e ->
            e.title.contains(book, ignoreCase = true) ||
                e.book.contains(book, ignoreCase = true) ||
                e.content.contains(book, ignoreCase = true) ||
                LoreKeys.decode(e.keys).any { it.contains(book, ignoreCase = true) }
        }
    } }
    // 只看某一本：点书名进去，再点一次（或点「看全部」）出来。
    // 900 条摊在一页上没法看，总得能一本一本翻。
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    val listed = remember(shown, only) { if (only == null) shown else shown.filter { it.book.trim() == only } }
    // Already in the order the book keeps (book, position, id), so the groups come out in that order.
    val grouped = remember(listed) { listed.groupBy { it.book.trim() } }

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "${name}的世界书",
                subtitle = if (selecting) "已选 ${selected.size} 条" else if (entries.isEmpty()) null else "${entries.size} 条 · 点开能改",
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, if (selecting) "退出多选" else "返回", {
                    if (selecting) cancelSelection() else onBack()
                }, page) },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entries.isNotEmpty()) GlassIconButton(Icons.Rounded.Checklist, if (selecting) "退出多选" else "多选", {
                            if (selecting) cancelSelection() else if (!importing) selecting = true
                        }, page)
                        if (!selecting) {
                            // 导入入口钉在顶栏上：以前它在列表最底下，条目一多就要翻半天才够得着。
                            GlassIconButton(
                                Icons.Rounded.FileDownload,
                                if (importing) "正在导入…" else "从别的 app 导入文件",
                                { if (!importing) importPicker.launch(arrayOf("*/*")) },
                                page,
                            )
                            GlassIconButton(Icons.Rounded.Add, "自己加一条", { onOpen(0) }, page)
                        }
                    }
                },
            )
            if (selecting) {
                GlassSurface(
                    Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = navBottom + 10.dp)
                        .fillMaxWidth().clickable(interactionSource = null, indication = null) {},
                    backdrop = page, style = palette.card, contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前显示 ${shown.size} 条 · 已选 ${selected.size} 条", color = palette.content, fontSize = 14.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { selected = LoreSelection.toggle(selected, shown.map { it.id }) }, enabled = !deleting && shown.isNotEmpty()) {
                                Text(if (shown.isNotEmpty() && shown.all { it.id in selected }) "取消当前全选" else "全选当前结果")
                            }
                            TextButton(onClick = { selected = emptySet() }, enabled = !deleting && selected.isNotEmpty()) { Text("清空选择") }
                        }
                        TextButton(onClick = { confirmDelete = selected.toSet() }, enabled = !deleting && selected.isNotEmpty(), modifier = Modifier.align(Alignment.End)) {
                            Text(if (deleting) "正在删除…" else "删除所选 ${selected.size} 条", color = if (selected.isNotEmpty()) palette.error else palette.contentSecondary)
                        }
                    }
                }
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fadeUnderTopBar(statusTop + TopBarHeight),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 10.dp, bottom = navBottom + if (selecting) 200.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 导入的那一块挪到最前头（以前在最底下，条目多了要翻半天）。
            if (!selecting) item(key = "foreign") {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = GlassShape.Rounded(22.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "从别的 app 搬过来：角色卡（PNG 图片卡也认）、世界书、记忆库、聊天记录都认，" +
                                "已经有的条目不会重复进来。",
                            color = palette.contentSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                        Box(
                            Modifier
                                .background(palette.content.copy(alpha = 0.07f), CircleShape)
                                .clickable(interactionSource = null, indication = null) { if (!importing) importPicker.launch(arrayOf("*/*")) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(if (importing) "正在导入…" else "导入文件", color = palette.content, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Notice(
                        "还没有世界书。世界观、人物、地点这类，一段就是一条：点右上角自己加，或者" +
                            "在「设置 › 数据与备份」里从别的 app 搬一本世界书过来。",
                    )
                }
            } else {
                item(key = "find") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("找一条") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (entries.isNotEmpty() && shown.isEmpty()) {
                item(key = "none") { Notice("世界书里没有和「${query.trim()}」有关的条目。") }
            }
            note?.let { text -> item(key = "note") { Notice(text) } }
            if (only != null) {
                item(key = "only") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "正在只看《$only》· ${listed.size} 条",
                            color = palette.content,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { only = null }) { Text("看全部") }
                    }
                }
            }
            // 搬进来的世界书一册一册排在前面，自己写的留在最后：这两样不是一回事，
            // 混在一堆里看不出哪条是从哪儿来的。
            for ((title, list) in grouped.filterKeys { it.isNotEmpty() } + grouped.filterKeys { it.isEmpty() }) {
                item(key = "h-$title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (title.isEmpty()) unbookedLabel(list) else "《$title》") + " · ${list.size} 条",
                            color = palette.contentSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 6.dp, top = 6.dp)
                                // 点书名（或「没归到书里」）＝ 只看这一本；再点一次回来。
                                .clickable(interactionSource = null, indication = null) {
                                    only = if (only == title) null else title
                                },
                        )
                        if (selecting) TextButton(onClick = { selected = LoreSelection.toggle(selected, list.map { it.id }) }, enabled = !deleting) {
                            Text(if (list.all { it.id in selected }) "取消选择" else if (book.isEmpty()) "选这本书" else "选当前条目")
                        }
                    }
                }
                items(list, key = { it.id }) { e ->
                    LoreRow(e, selecting, e.id in selected, deleting,
                        onSelect = { selected = LoreSelection.toggle(selected, listOf(e.id)) }, onOpen = { onOpen(e.id) }, onToggle = { on ->
                        scope.launch { c.db.lore().update(e.copy(enabled = on, updatedAt = System.currentTimeMillis())) }
                    })
                }
            }
        }
    }
    confirmDelete?.let { ids ->
        val target = ta?.id
        Dialog(onDismissRequest = { if (!deleting) confirmDelete = null }) {
            GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(22.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("删除 ${ids.size} 条世界书？", color = palette.content, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text("只删除${name}这次选中的世界书，包含已停用的条目。删除后不能撤销；需要保留的话，请先导出备份。", color = palette.contentSecondary, fontSize = 14.sp, lineHeight = 21.sp)
                    Row(Modifier.align(Alignment.End)) {
                        TextButton(onClick = { confirmDelete = null }, enabled = !deleting) { Text("取消") }
                        TextButton(enabled = !deleting && target != null, onClick = {
                            if (target != null && !deleting) {
                                deleting = true
                                // Leaving the page must not cancel half of a large deletion.
                                c.appScope.launch(Dispatchers.Main.immediate) {
                                    try {
                                        val count = c.db.lore().deleteSelected(target, ids.toList())
                                        selected = selected - ids
                                        note = "已删除 $count 条世界书。"
                                        confirmDelete = null
                                        selecting = false
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        note = "删除失败，请重试。"
                                        confirmDelete = null
                                    } finally {
                                        deleting = false
                                    }
                                }
                            }
                        }) { Text(if (deleting) "正在删除…" else "确认删除", color = palette.error) }
                    }
                }
            }
        }
    }
}

/**
 * 没归到书里的那一堆，按来路分开叫：自己写的，和「导入时没带上书名的」。
 *
 * 后者是旧版本的产物：那时候书名只认世界书根对象里的 name，而酒馆导出的世界书没有那个字段，
 * 所以不管导几本，条目全挤在这儿。看标签就能认出这一批，重新导一次就归位了。
 */
private fun unbookedLabel(list: List<LoreEntity>): String =
    if (list.all { it.source == MemoryEntity.SOURCE_ME }) "自己写的" else "没归到书里"

/** One entry: what it is called, the words that lead to it, and whether the TA may find it at all. */
@Composable
private fun LoreRow(e: LoreEntity, selecting: Boolean, selected: Boolean, busy: Boolean, 
    onSelect: () -> Unit, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    val keys = LoreKeys.decode(e.keys)
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClickLabel = if (selecting) "选择" else "打开", onClick = if (selecting) onSelect else onOpen),
        shape = GlassShape.Rounded(22.dp),
        contentPadding = PaddingValues(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = null, enabled = !busy)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    // 旧数据里有些标题是从正文按字数截的，带着换行：只显示第一行，
                    // 不然标题看着像正文的复读。
                    e.title.substringBefore('\n').trim().ifBlank { "没起名字" },
                    color = palette.content,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (keys.isEmpty()) "没有关键词，只有说到「${e.title.substringBefore('\n').trim().ifBlank { "它" }}」时才可能查到" else keys.joinToString("、"),
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (!selecting) Switch(checked = e.enabled, onCheckedChange = onToggle, colors = glassSwitchColors())
        }
    }
}

@Composable
private fun Notice(text: String) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        style = palette.notice,
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(text, color = palette.content, fontSize = 14.sp, lineHeight = 21.sp)
    }
}
