package com.cleo.cleos.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.CrashLog
import com.cleo.cleos.R
import com.cleo.cleos.Releases
import com.cleo.cleos.ai.SearchService
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.data.WorkspaceEntry
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.data.PatRecord
import com.cleo.cleos.data.Pats
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.chat.ChatType
import com.cleo.cleos.ui.chat.PatDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the person is called, how much of the conversation goes along, and the avatars beside messages. */
@Composable
internal fun ChatPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    Section("你") {
        Field("你的名字", vm.userName, { vm.userName = it })
    }

    Section("TA 记得多少") {
        Text("每次原样带上最近 ${vm.historySize} 条消息", color = palette.content, fontSize = 14.sp)
        Slider(
            value = vm.historySize.toFloat(),
            onValueChange = { vm.historySize = (it / 10f).roundToInt() * 10 },
            valueRange = 10f..200f,
            steps = 18,
        )
        Text(
            "更早的，TA 会自己整理成前情提要带着，不会一下子忘掉；在聊天里往上翻，能看到分界的那一行，点开能看、能改。原样带得越多，细节记得越清楚，每次花的 token 也越多。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }

    Section("字号") {
        val type = ChatType(settings.chatTextSize)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatType.SIZES.forEach { size ->
                val label = when (size) {
                    14 -> "小"
                    15 -> "标准"
                    16 -> "大"
                    else -> "特大"
                }
                Chip("$label $size", selected = settings.chatTextSize == size) { vm.setChatTextSize(size) }
            }
        }
        // What a bubble looks like at that size.
        GlassSurface(
            style = palette.bubble,
            shape = GlassShape.Rounded(20.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("今天也辛苦啦。晚饭吃了吗？我刚才还在想，要不要提醒你早点睡。", color = palette.content, style = type.body)
        }
    }

    Section("消息间距") { MessageSpacingControls(settings.messageSpacing, vm::setMessageSpacing) }

    // Moved here from the home page, where they sat among the TA's things.
    ListCard("头像") {
        ExplainedSwitch("聊天里显示头像", "消息旁边放上各自的头像", null, settings.chatAvatars) { vm.setChatAvatars(it) }
        ExplainedSwitch("默认展开语音文字", "关闭时显示紧凑语音条，点“查看文字”展开；展开或收起都不影响播放", null, settings.expandVoiceText, vm::setExpandVoiceText)
        if (settings.chatAvatars) {
            RowDivider(inset = 0.dp)
            ExplainedSwitch(
                "每条都显示",
                "像微信那样每条消息都带头像",
                "像微信那样每条消息都带头像；关着时，连着的几条只在最后一条旁边放一个。",
                settings.avatarEachMessage,
            ) { vm.setAvatarEachMessage(it) }
        }
    }

    // 拍一拍: a double tap on an avatar says it, and the long press in the chat opens the very
    // same dialog — here as well, since a double tap is not a thing anyone is told to try.
    ListCard("拍一拍") {
        var editing by remember { mutableStateOf(false) }
        val said = Pats.line(PatRecord(Pats.AI, 1, settings.patVerb, settings.patSuffix), vm.aiName)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("双击头像拍一下", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "现在拍出来是「$said」。TA 不会为它单独回话，下次你说话时才知道。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Chip("改一改", selected = false) { editing = true }
        }
        RowDivider(inset = 0.dp)
        ExplainedSwitch("拍的时候震一下", "拍一下时手机跟着轻轻震一下", null, settings.patBuzz) { vm.setPatBuzz(it) }
        if (editing) {
            PatDialog(
                aiName = vm.aiName,
                verb = settings.patVerb,
                suffix = settings.patSuffix,
                buzz = settings.patBuzz,
                onSave = { verb, suffix, buzz ->
                    vm.setPat(verb, suffix, buzz)
                    editing = false
                },
                onDismiss = { editing = false },
            )
        }
    }
}

/** The service a voice message is turned into words by, before the TA reads it. */
@Composable
internal fun VoiceInputPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val hasVoiceKey by vm.hasVoiceKey.collectAsStateWithLifecycle()
    Section("转文字的服务") {
        Text(
            "在聊天里按住输入框说话，松开就变成文字填进输入框，改好再发；往上滑再松开是取消。" +
                "语音先转成文字再给 TA 看，所以要接一个转文字的服务，和聊天的模型分开选。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Voice.presets.forEach { p ->
                Chip(p.name, selected = vm.voiceBaseUrl.trimEnd('/') == p.baseUrl) { vm.applyVoicePreset(p) }
            }
        }
        Field("接口地址", vm.voiceBaseUrl, { vm.voiceBaseUrl = it }, keyboardType = KeyboardType.Uri)
        Field("模型", vm.voiceModel, { vm.voiceModel = it })
        OutlinedTextField(
            value = vm.voiceKeyInput,
            onValueChange = { vm.voiceKeyInput = it },
            label = { Text("API Key") },
            placeholder = { if (hasVoiceKey) Text("已保存（不再显示）；要换就重新填") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.voiceKeyInput.isNotBlank()) Chip("保存 Key", selected = true) { vm.saveVoiceKey() }
            if (vm.voiceBaseUrl.isNotBlank()) Chip(if (vm.voiceTesting) "正在试…" else "试一下", selected = false) { vm.testVoice() }
        }
        Text(
            if (hasVoiceKey) "这个地址的 Key 已经有了。" else "Key 跟着地址存：和哪个 TA 聊天用的是同一个地址，就不用再填。模型名以服务商的说明为准。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        vm.voiceResult?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
    }
}

/**
 * 联网搜索：选一家搜索服务，去它那儿申请一个 Key，填进来。
 *
 * 每一家都把申请 Key 的入口写在旁边 —— 「去申请」这种事不该让人自己去找。
 * Key 按服务分别存（SecretStore），所以在几家之间切换不会把谁的 Key 弄丢。
 */
@Composable
internal fun WebSearchPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasKey by vm.hasSearchKey.collectAsStateWithLifecycle()
    val chosen = SearchService.of(vm.searchService)

    Section("让 TA 上网搜") {
        Text(
            "开着的时候，TA 遇到不知道的事、或者你让它查东西，会去网上搜，再把结果读出来给你。" +
                "搜索得接一家服务：下面每一家旁边都有「去申请 Key」，点开就是它的注册页，" +
                "注册好把 Key 填进来就能用。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        ExplainedSwitch(
            "联网搜索",
            "聊天输入框左边那个小地球就是它",
            "开着时 TA 可以联网搜索；关掉就只靠它自己知道的。随时能改，输入框那个开关和这里是同一个。",
            ToolGroup.Search in settings.tools,
        ) { vm.setTool(ToolGroup.Search, it) }
    }

    Section("用哪一家") {
        SearchService.entries.forEachIndexed { index, service ->
            val isChosen = vm.searchService == service.name
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable(interactionSource = null, indication = null) { vm.chooseSearch(service) },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        service.label,
                        color = if (isChosen) palette.accentContent else palette.content,
                        fontSize = 15.sp,
                        fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (isChosen) Text("已选", color = palette.accentContent, fontSize = 12.sp)
                }
                Text(service.hint, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                Text(
                    "去申请 Key ↗",
                    color = palette.accentContent,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.keyUrl))) }
                    },
                )
            }
            if (index != SearchService.entries.lastIndex) RowDivider(inset = 0.dp)
        }
    }

    Section("Key") {
        if (chosen == null) {
            Text("先在上面点一家，再填它的 Key。", color = palette.contentSecondary, fontSize = 13.sp)
            return@Section
        }
        OutlinedTextField(
            value = vm.searchKeyInput,
            onValueChange = { vm.searchKeyInput = it },
            label = { Text("${chosen.label} 的 API Key") },
            placeholder = { if (hasKey) Text("已保存（不再显示）；要换就重新填") else Text("粘在这里") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.searchKeyInput.isNotBlank()) Chip("保存 Key", selected = true) { vm.saveSearchKey() }
            Chip(if (vm.searchTesting) "正在试…" else "试一下", selected = false) { vm.testSearch() }
        }
        Text(
            if (hasKey) "${chosen.label} 的 Key 已经有了。" else "Key 只存在这台手机上，而且按服务分开存：以后换一家再换回来，不用重填。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        vm.searchResult?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
    }
}

/**
 * TA 的工作区：它写的笔记、草稿都在这儿。
 *
 * 这一页是给人看的那一半 —— 没有它，TA 写了什么就只能往回翻聊天记录、点开那一行小字。
 * 这里只负责看、导出、删；写是 TA 自己的事，而且它写之前那边是有授权的。
 */
@Composable
internal fun WorkspacePage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = vm.workspaceEntries
    var exporting by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val file = exporting
        exporting = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = vm.workspaceText(file)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: error("写不进去")
                }.isSuccess
            }
            vm.noteWorkspace(if (ok) "导出好了，看看手机的「下载」里" else "导出没成")
        }
    }

    LaunchedEffect(Unit) { vm.refreshWorkspace() }

    val (count, bytes) = vm.workspaceUsage
    Section("TA 的工作区") {
        Text(
            "这是 TA 自己的一个文件夹：你让它记点什么、写份草稿，它就放在这儿。" +
                "在这里你能看它写了什么、导出到手机，或者删掉。要不要让它有这个文件夹，在「能做的事」里那个开关。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Text(
            if (count == 0) "现在还是空的。" else "现在有 $count 个文件，共 ${sizeText(bytes)}。",
            color = palette.content,
            fontSize = 13.sp,
        )
    }

    Section(if (vm.workspacePath.isEmpty()) "里面的东西" else "/${vm.workspacePath}") {
        if (vm.workspacePath.isNotEmpty()) {
            Chip("← 上一层", selected = false) { vm.openWorkspace(vm.workspacePath.substringBeforeLast('/', "")) }
        }
        if (entries.isEmpty()) {
            Text("这里没有东西。", color = palette.contentSecondary, fontSize = 13.sp)
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) RowDivider(inset = 0.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (entry.directory) vm.openWorkspace(entry.path) else vm.openWorkspaceFile(entry.path)
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.Description,
                    contentDescription = null,
                    tint = palette.contentSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, color = palette.content, fontSize = 15.sp)
                    Text(
                        if (entry.directory) "文件夹" else "${sizeText(entry.size)} · ${whenText(entry.modifiedAt)}",
                        color = palette.contentSecondary,
                        fontSize = 11.sp,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = palette.contentSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        vm.workspaceNote?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
    }

    vm.workspaceOpen?.let { (path, text) ->
        AlertDialog(
            onDismissRequest = vm::closeWorkspaceFile,
            title = { Text(path.substringAfterLast('/'), fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text.ifEmpty { "（这个文件是空的）" },
                        color = palette.content,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    Text("${text.length} 字", color = palette.contentSecondary, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exporting = path
                    save.launch(path.substringAfterLast('/'))
                }) { Text("导出") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleting = path }) { Text("删除", color = palette.error) }
                    TextButton(onClick = vm::closeWorkspaceFile) { Text("关闭") }
                }
            },
        )
    }

    deleting?.let { path ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删掉「${path.substringAfterLast('/')}」？") },
            text = { Text("它会从工作区里消失，删了就找不回来了。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteWorkspace(path)
                    deleting = null
                    vm.closeWorkspaceFile()
                }) { Text("删掉", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("算了") } },
        )
    }
}

/** 字节数说成人话。 */
internal fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes 字节"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 改动时间说成「刚刚 / 3 小时前 / 10 月 16 日」。 */
private fun whenText(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> java.text.SimpleDateFormat("M 月 d 日", java.util.Locale.CHINA).format(java.util.Date(at))
    }
}

/** The wallpaper, the glass over it, and the colour of the person's own bubbles. */
@Composable
internal fun LookPage(vm: SettingsViewModel, onOpenLab: () -> Unit, onOpenBubbleLab: () -> Unit) {
    val palette = LocalGlassPalette.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setWallpaper(uri)
    }

    Section("壁纸") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (vm.wallpaperBusy) "正在换…" else "换壁纸", selected = false) {
                if (!vm.wallpaperBusy) {
                    wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            if (settings.wallpaper != null) Chip("用回默认", selected = false) { vm.resetWallpaper() }
        }
        vm.wallpaperError?.let { Text(it, color = palette.error, fontSize = 13.sp) }
    }

    Section("玻璃") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("跟随壁纸", selected = settings.glassMode == GlassMode.Auto) { vm.setGlassMode(GlassMode.Auto) }
            Chip("浅色", selected = settings.glassMode == GlassMode.Light) { vm.setGlassMode(GlassMode.Light) }
            Chip("深色", selected = settings.glassMode == GlassMode.Dark) { vm.setGlassMode(GlassMode.Dark) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(interactionSource = null, indication = null, onClickLabel = "打开", onClick = onOpenLab),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("玻璃实验室", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("拖一块玻璃，看每个参数在做什么", color = palette.contentSecondary, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.contentSecondary)
        }
    }

    Section("气泡") {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpenBubbleLab), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("气泡实验室", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                val choices = com.cleo.cleos.ui.chat.BubbleThemes.choices.toMap()
                Text("我：${choices[settings.myBubbleTheme] ?: "默认玻璃"} · TA：${choices[settings.taBubbleThemes[vm.companionId.toString()]] ?: "默认玻璃"}",
                    color = palette.contentSecondary, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.contentSecondary)
        }
    }
}

/** Backups out and back in, and memory in from anywhere else. */
@Composable
internal fun DataPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var confirmUndo by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestore = uri
        // 先解析、校验、数一数，给出概览再让人决定（见 BackupService.preview）。
        uri?.let { vm.previewBackup(it) }
    }
    val memoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMemoryFile(uri)
    }

    Section("备份") {
        Text(
            "把每个 TA、聊天、日记、信、记忆、收藏、待办、表情包、图片和语音打包成一个文件。换手机、重装之前先导出一份。API Key 不会导出。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (vm.backupBusy) "正在处理…" else "导出备份", selected = false) {
                if (!vm.backupBusy) exportPicker.launch("cleos-备份-${java.time.LocalDate.now()}.zip")
            }
            Chip("从备份恢复", selected = false) {
                if (!vm.backupBusy) restorePicker.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
        if (vm.canUndoRestore) {
            Chip("撤销上次恢复", selected = false) { confirmUndo = true }
        }
    }

    Section("从别的 app 搬过来") {
        Text(
            "选一个文件，四样都认：角色卡（PNG 图片卡也认；一张新开一个 TA，文件里几张就开几个，别的东西都归第一个）、" +
                "世界书（进「世界书」，聊天时 TA 自己去查）、记忆库、历史聊天（Operit JSON 也认，多段对话分别保留，归当前 TA）。也可以只搬记忆：" +
                "JSON、纯文本、Markdown、Word（.docx）都行，一段或一行一件，「名字: 内容」拆成两半。Word 只读取正文和表格文字，普通截图需要先提取文字。" +
                "记忆同名的并进已有那件，缺的细节补上；一类最多记 10 件，多的放进「世界书」；世界书里已经有的条目不会重复进来。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        // Any type: a file passed along through a chat app often comes back without a JSON type.
        Chip(if (vm.memoryBusy) "正在导入…" else "选个文件", selected = false) {
            if (!vm.memoryBusy) memoryPicker.launch(arrayOf("*/*"))
        }
        vm.memoryMessage?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
    }

    vm.backupMessage?.let { message ->
        Section(null) { Text(message, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
    }

    // 导入前先给概览：解析 + 校验完才让点「恢复」。
    val preview = vm.backupPreview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = { vm.clearBackupPreview(); pendingRestore = null },
            title = { Text(if (preview.ok) "用这份备份替换现在的内容？" else "这份备份导入不了") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (preview.ok) {
                        Text("现在的 TA、聊天、日记、信、记忆、收藏和待办会被备份里的内容替换掉。恢复之前会自动把现在的留一份，恢复完可以撤销。")
                        preview.counts?.let { Text(it, color = palette.content, fontSize = 13.sp) }
                        preview.warning?.let { Text(it, color = palette.error, fontSize = 12.sp, lineHeight = 17.sp) }
                        Text("不认识的字段会跳过；恢复完会显示实际恢复的数量。", color = palette.contentSecondary, fontSize = 12.sp)
                    } else {
                        Text(preview.error ?: "读不出来", color = palette.error)
                        Text("现有数据没有动。", color = palette.contentSecondary, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                if (preview.ok) TextButton(onClick = {
                    val uri = pendingRestore
                    vm.clearBackupPreview()
                    pendingRestore = null
                    if (uri != null) vm.restoreBackup(uri)
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { vm.clearBackupPreview(); pendingRestore = null }) { Text(if (preview.ok) "取消" else "知道了") }
            },
        )
    }

    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("撤销上次恢复？") },
            text = { Text("回到恢复之前的 TA、聊天、日记和待办。恢复之后新写的会没有。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUndo = false
                    vm.undoRestore()
                }) { Text("撤销") }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("取消") } },
        )
    }
}


/** The version and where new ones are, the last crash when there was one, privacy, and credits. */
@Composable
internal fun AboutPage() {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val c = com.cleo.cleos.ui.common.appContainer()
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    var showNotices by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Section("Cleos ${version.orEmpty()} · 二改版") {
        Text(
            "这是拿原作者的 Cleos（github.com/jqzhang921-sudo/cleos）改出来的版本。加的和改的：" +
                "发文件给 TA 看（TXT / MD / PDF / DOCX）、按住输入框说话转文字、TA 自己的工作区、" +
                "读网页和联网搜索，另外修了几个导入和 PDF 的老问题。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Text(
            "更新不走原项目的渠道：原来那个「检查更新」和「去蓝奏云看新版」在这里关掉了 —— " +
                "它们给的都是原版，装上去会把上面这些改动盖掉。新版本还是从你拿到这个 apk 的地方取，" +
                "直接覆盖安装就行，聊天记录、日记都还在（**别先卸载**，卸载会把这台手机上的东西一起清掉）。" +
                "真要重装，先在「数据与备份」里导出一份。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }

    var diagnosticHint by remember { mutableStateOf<String?>(null) }
    var diagnosticBusy by remember { mutableStateOf(false) }
    fun copyDiagnostic(pinned: Boolean) {
        if (diagnosticBusy) return
        diagnosticBusy = true
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) { c.toolDiagnostics.report(pinned) }
                if (report == null) {
                    diagnosticHint = if (pinned) "还没有保留的异常。若刚才出现了问题，可以先点「标记刚才的问题」。" else "还没有记录，正常聊一次后再看。"
                } else {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Cleos 工具诊断", report))
                    diagnosticHint = "复制好了，发送前可以先看一眼。"
                }
            } finally { diagnosticBusy = false }
        }
    }
    Section("工具诊断") {
        Text("自动在这台手机上保留最近的精简记录，以及首次接口或 MCP 异常。下次偶发时不用连电脑，在这里复制就行；" +
            "如果没有报错、但 TA 说不能用工具，可以立即标记刚才的问题。",
            color = palette.content, fontSize = 12.sp, lineHeight = 18.sp)
        Text("记录模型名、工具名、数量和错误类别；不记录 Key、聊天正文、工具参数或原始响应。只占约 192 KB，循环更新，不自动上传，也不进入备份。异常保留到你清除或重新标记。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("复制异常记录", selected = false) { copyDiagnostic(true) }
            Chip("复制最近记录", selected = false) { copyDiagnostic(false) }
            Chip("标记刚才的问题", selected = false) {
                if (!diagnosticBusy) {
                    diagnosticBusy = true
                    scope.launch {
                        try {
                            val kept = withContext(Dispatchers.IO) { c.toolDiagnostics.pin(); c.toolDiagnostics.report(true) != null }
                            diagnosticHint = if (kept) "已保留当前现场，可以复制异常记录。" else "还没有记录，正常聊一次后再看。"
                        } finally { diagnosticBusy = false }
                    }
                }
            }
            Chip("清除记录", selected = false) {
                if (!diagnosticBusy) {
                    diagnosticBusy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { c.toolDiagnostics.clear() }
                            diagnosticHint = "已清除；之后会重新记录并捕获首次异常。"
                        } finally { diagnosticBusy = false }
                    }
                }
            }
        }
        diagnosticHint?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
    }

    // 一键反馈：把该带的（版本、系统、页面、脱敏错误）整理好，人自己看、自己发。
    var feedbackOpen by remember { mutableStateOf(false) }
    Section("反馈问题") {
        Text(
            "把版本、系统、当前页面和最近的脱敏错误整理成一段，先给你看：可以补描述、选截图，再复制或导出。" +
                "不含密钥、Token、聊天正文和私人附件，也不会自动发给任何服务。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Chip("反馈问题", selected = false) { feedbackOpen = true }
    }
    if (feedbackOpen) FeedbackDialog(page = "设置 › 关于", onDismiss = { feedbackOpen = false })

    // 虚拟屏探针：验证「角色在独立虚拟屏里刷抖音」在这台手机上是否可行，只做验证。
    Section("虚拟屏探针（验证用）") {
        Text(
            "分步验证：创建虚拟屏 → 把抖音启动到那块屏 → 读一帧画面 → 往该屏滑动 → 释放。" +
                "每一步显示真实结果（包括 SecurityException 这类具体错误），不参与聊天。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Chip("打开探针", selected = false) {
            context.startActivity(
                android.content.Intent(context, com.cleo.cleos.ui.probe.VirtualScreenProbeActivity::class.java),
            )
        }
    }

    // The last crash, to send to whoever makes the app (CrashLog): there only after one.
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    var crashHint by remember { mutableStateOf<String?>(null) }
    crash?.let { text ->
        Section("上次闪退") {
            Text(
                "Cleos 上次闪退了（${text.lineSequence().first().substringAfter("，").substringBefore(" 闪退")}）。" +
                    "复制下来发给做 App 的人，就能知道是哪里出的错；里面是出错的位置，不是聊天内容，发之前也可以先看一眼。",
                color = palette.content,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("复制闪退记录", selected = false) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Cleos 闪退记录", text))
                    crashHint = "复制好了。"
                }
                Chip("清掉", selected = false) {
                    CrashLog.clear(context)
                    crash = null
                    crashHint = null
                }
            }
            crashHint?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
        }
    }

    // Something that failed out of sight and was let go of, with the app going on (CrashLog.note).
    var note by remember { mutableStateOf(CrashLog.readNote(context)) }
    var noteHint by remember { mutableStateOf<String?>(null) }
    note?.let { text ->
        Section("后台出错") {
            Text(
                "Cleos 后台有件事没做成（${text.lineSequence().first().substringAfter("，").substringBefore(" 后台出错")}）：" +
                    "App 没有退出，就是那件事被丢下了。复制下来发给做 App 的人，就能知道是哪里出的错；" +
                    "里面是出错的位置，不是聊天内容，发之前也可以先看一眼。",
                color = palette.content,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("复制出错记录", selected = false) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Cleos 出错记录", text))
                    noteHint = "复制好了。"
                }
                Chip("清掉", selected = false) {
                    CrashLog.clearNote(context)
                    note = null
                    noteHint = null
                }
            }
            noteHint?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
        }
    }

    Section("隐私") {
        Text(
            "聊天、日记和待办都只存在这台手机上。API Key 用系统密钥库加密。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }

    Section("许可与版权") {
        // Credit the data's licence asks for, where the people who get the app can see it.
        Text(
            "Cleos 原创代码与已授权贡献采用 Apache-2.0；第三方内容保留各自的许可。语音条「在耳边」用的是 J. M. Arend、A. Neidhardt、C. Pörschmann 发布的 Neumann KU100 近场 HRIR 数据" +
                "（Zenodo，doi:10.5281/zenodo.4297951，CC BY 4.0；Cleos 只留了三档距离、换了存法），" +
                "做法移植自 binaural-voice（github.com/Saekisui/binaural-voice，MIT）。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Chip("看许可全文", selected = false) { showNotices = true }
    }

    if (showNotices) {
        // Read from the files that ship with the app, so what is shown is what the licences say, word for word.
        val notices = remember {
            NOTICES.joinToString("\n\n————\n\n") { path ->
                runCatching { context.assets.open(path).use { it.readBytes().decodeToString() } }.getOrDefault("")
            }
        }
        AlertDialog(
            onDismissRequest = { showNotices = false },
            title = { Text("许可与版权") },
            text = {
                Text(
                    notices,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showNotices = false }) { Text("好") } },
        )
    }
}
