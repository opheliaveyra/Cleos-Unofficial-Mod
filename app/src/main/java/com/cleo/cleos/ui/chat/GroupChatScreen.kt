package com.cleo.cleos.ui.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.ai.GroupChatRules
import com.cleo.cleos.ai.GroupMemoryRules
import com.cleo.cleos.ai.GroupToolRules
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.MessageAudios
import com.cleo.cleos.data.BubbleSpacing
import com.cleo.cleos.data.db.GroupMessageEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import com.cleo.cleos.ui.settings.Chip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun GroupChatScreen(groupId: Long, onBack: () -> Unit, onOpenImage: (String) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val dao = c.db.groups()
    val group by remember(groupId) { dao.observeGroup(groupId) }.collectAsStateWithLifecycle(null)
    val members by remember(groupId) { dao.observeMembers(groupId) }.collectAsStateWithLifecycle(emptyList())
    val rows by remember(groupId) { dao.observeMessages(groupId) }.collectAsStateWithLifecycle(emptyList())
    val notes by remember(groupId) { dao.observeMemories(groupId) }.collectAsStateWithLifecycle(emptyList())
    val tas by c.companions.all.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(com.cleo.cleos.data.AppSettings())
    val working by c.groupChat.working.collectAsStateWithLifecycle()
    val live by c.groupChat.streaming.collectAsStateWithLifecycle()
    val progress by c.groupChat.progress.collectAsStateWithLifecycle()
    val generation by c.groupChat.generation.collectAsStateWithLifecycle()
    val busy = groupId in working
    var tickNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(busy) {
        while (busy) { tickNanos = System.nanoTime(); delay(1000) }
    }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var draft by rememberSaveable(groupId) { mutableStateOf("") }
    var mentions by rememberSaveable(groupId) { mutableStateOf(listOf<Long>()) }
    /** 这次要一起发到群里的图（发之前得看得见、能删）。 */
    var pictures by remember(groupId) { mutableStateOf(emptyList<MessageImage>()) }
    var pickingPicture by remember(groupId) { mutableStateOf(false) }
    /** 「＋」展开的那层：图片、语音、点名都收在里面 —— 输入栏只留 ＋ / 输入框 / 发送。 */
    var attachOpen by rememberSaveable(groupId) { mutableStateOf(false) }
    var loaded by remember(groupId, generation) { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var editingMembers by remember { mutableStateOf(false) }
    var savingMembers by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickingPicture = false
        if (uri != null) scope.launch {
            runCatching { c.images.import(uri, maxEdge = 2048, prefix = "group-") }
                .onSuccess { pictures = pictures + MessageImage(it.file, it.width, it.height) }
                .onFailure { problem = "这张图没能读进来" }
        }
    }
    // 按住说话：和单聊共用同一个层（VoiceHold.kt）与同一个录音器（16 kHz 单声道 WAV）。
    val context = androidx.compose.ui.platform.LocalContext.current
    val recorder = remember { VoiceRecorder(c.images.dir) }
    var recording by remember { mutableStateOf(false) }
    var recordedMs by remember { mutableLongStateOf(0L) }
    var loudness by remember { mutableFloatStateOf(0f) }
    /** 手指现在指着哪边：左转文字 / 右直接发 / null 上滑取消。 */
    var holdTarget by remember { mutableStateOf<HoldTarget?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    /** 录音期间/转写中的一句话提示（"正在转成文字…"、失败原因）。 */
    var voiceHint by remember { mutableStateOf<String?>(null) }
    var transcribingVoice by remember { mutableStateOf(false) }
    /** 轻点输入框要能把键盘叫出来（长按层会把轻点吃掉，见 [HoldToTalkLayer]）。 */
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) voiceHint = "要给 Cleos 用话筒的权限，才能发语音"
    }
    // 拍照：和单聊共用同一个组件（PhotoCapture.kt）—— 拍完先预览，确认了才进待发送的图片。
    val photo = rememberPhotoCapture()
    var photoBusy by remember { mutableStateOf(false) }
    var photoProblem by remember { mutableStateOf<String?>(null) }
    // 「我在本群的头像」：选图 → 裁剪 → 存成这个群专属的（见 UserFaces）。
    var myFaceCrop by remember { mutableStateOf<android.net.Uri?>(null) }
    val myFacePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) myFaceCrop = uri
    }
    photo.pending?.let { shot ->
        PhotoPreviewDialog(
            file = shot,
            problem = photoProblem ?: photo.problem,
            busy = photoBusy,
            onRetake = {
                photo.discard()
                photoProblem = null
                photo.take()
            },
            onCancel = {
                photo.discard()
                photoProblem = null
            },
            onConfirm = {
                photoBusy = true
                scope.launch {
                    val stored = runCatching {
                        c.images.import(android.net.Uri.fromFile(shot), maxEdge = 2048, prefix = "group-camera-")
                    }.getOrNull()
                    photoBusy = false
                    if (stored == null) {
                        photoProblem = "这张照片没能处理 —— 重拍一张，或者从相册选"
                    } else {
                        pictures = pictures + MessageImage(stored.file, stored.width, stored.height)
                        photo.discard()
                        photoProblem = null
                    }
                }
            },
        )
    }
    val player = remember { arrayOfNulls<android.media.MediaPlayer>(1) }
    var playing by remember { mutableStateOf<String?>(null) }
    fun stopPlaying() {
        player[0]?.let { runCatching { it.stop() }; it.release() }
        player[0] = null
        playing = null
    }
    fun playVoice(file: String) {
        val again = playing == file
        stopPlaying()
        if (again) return
        playing = file
        val p = android.media.MediaPlayer()
        runCatching {
            p.setDataSource(c.images.file(file).path)
            p.setOnCompletionListener { stopPlaying() }
            p.prepare()
            p.start()
        }.onSuccess { player[0] = p }.onFailure { p.release(); playing = null; problem = "这段语音放不了" }
    }
    DisposableEffect(groupId) { onDispose { stopPlaying() } }

    fun act(block: suspend () -> Unit) { scope.launch {
        problem = null
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { problem = e.message ?: "没有完成，请再试一次" }
    } }

    /**
     * 长按开始录：**和单聊同一个检查顺序**（先看转文字服务配了没 → 权限 → 话筒开不开）。
     * 任何一步没过都返回 false，并在界面上留一句为什么。
     */
    fun startVoice(): Boolean {
        val s = settings
        when {
            s.voiceBaseUrl.isBlank() || s.voiceModel.isBlank() -> {
                voiceHint = "还没接语音转文字的服务，去设置「发语音」里选一个"
                return false
            }
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                mic.launch(android.Manifest.permission.RECORD_AUDIO)
                return false
            }
            !recorder.start() -> {
                voiceHint = "话筒打不开，可能正被别的应用用着"
                return false
            }
            else -> {
                stopPlaying()
                recordedMs = 0L
                cancelling = false
                holdTarget = HoldTarget.Text
                recording = true
                return true
            }
        }
    }

    /**
     * 松手：**取消 / 直接发 / 转文字**，和单聊一致。
     *
     * 转文字进输入框（还能改）；直接发是语音消息 —— 它转写后照样进模型（见 GroupChatRepository.sendVoice）。
     * **转写失败不动输入框里已经打好的字。**
     */
    fun finishVoice(target: HoldTarget?) {
        if (!recording) return
        recording = false
        cancelling = false
        holdTarget = null
        if (target == null) {
            recorder.cancel()
            return
        }
        val clip = recorder.stop()
        if (clip == null) {
            voiceHint = "说话时间太短了"
            return
        }
        if (target == HoldTarget.Voice) {
            act { c.groupChat.sendVoice(groupId, clip, mentions); mentions = emptyList() }
            return
        }
        transcribingVoice = true
        val s = settings
        scope.launch {
            val text = runCatching {
                c.transcriber.transcribe(s.voiceBaseUrl, s.voiceModel, c.images.file(clip.file)).trim()
            }.getOrNull()
            transcribingVoice = false
            if (text.isNullOrBlank()) {
                // 失败不动输入框：打好的字不能因为一次没听清就没了。
                voiceHint = "没听清，再说一次？（打好的字还在）"
            } else {
                draft = text.take(GroupChatRules.MAX_TEXT)
            }
        }
    }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val t0 = System.currentTimeMillis()
        while (recording) {
            recordedMs = System.currentTimeMillis() - t0
            loudness = recorder.level
            if (recordedMs >= com.cleo.cleos.ai.Voice.MAX_MS) finishVoice(HoldTarget.Text)
            delay(100)
        }
    }
    LaunchedEffect(voiceHint) {
        if (voiceHint != null) { delay(2400); voiceHint = null }
    }
    // ---- 下面这些是界面自己的状态（转写/录音只管上面那一层）----
    var selectedText by remember { mutableStateOf<String?>(null) }
    var actionMessageId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var toolMessageId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var memoriesOpen by rememberSaveable(groupId, generation) { mutableStateOf(false) }
    var memorySearch by rememberSaveable(groupId, generation) { mutableStateOf("") }
    var memoryEditor by rememberSaveable(groupId, generation) { mutableStateOf(false) }
    var editingMemoryId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var memorySourceId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var deletingMemoryId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var savingMemory by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var composerHeight by remember { mutableIntStateOf(120) }
    val density = LocalDensity.current
    val bubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.7f).dp
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val bottom = with(density) { composerHeight.toDp() }
    val active = tas.map { it.id }.toSet()
    val participating = members.filter { it.active }
    val availableMembers = participating.filter { it.companionId in active }.map { it.companionId }.toSet()
    LaunchedEffect(groupId) { c.settings.setCurrentGroup(groupId) }
    LaunchedEffect(group?.id, generation) {
        if (group != null) {
            val saved = dao.group(groupId)
            draft = saved?.draft.orEmpty(); mentions = GroupChatRules.ids(saved?.mentions ?: "[]"); loaded = true
        }
    }
    LaunchedEffect(draft, mentions, loaded, generation) {
        if (loaded) { delay(250); c.groupChat.saveDraft(groupId, draft, mentions, generation) }
    }
    LaunchedEffect(members, active, loaded) {
        if (loaded && members.isNotEmpty() && active.isNotEmpty()) mentions = mentions.filter { it in availableMembers }
    }
    val latestDraft by rememberUpdatedState(Triple(draft, mentions, loaded))
    DisposableEffect(groupId, generation) {
        val epoch = generation
        onDispose {
            val snapshot = latestDraft
            if (snapshot.third) c.appScope.launch { c.groupChat.saveDraft(groupId, snapshot.first, snapshot.second, epoch) }
        }
    }
    LaunchedEffect(rows.size, rows.lastOrNull()?.images, live.values.sumOf { it.length }) {
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (rows.isNotEmpty() && last >= (list.layoutInfo.totalItemsCount - 3).coerceAtLeast(0)) list.scrollToItem(rows.lastIndex)
    }
    // 「＋」面板一展开就占了地方：把最后一条推上来，别让它被压在面板底下。
    LaunchedEffect(attachOpen) {
        if (attachOpen && rows.isNotEmpty()) runCatching { list.animateScrollToItem(rows.lastIndex) }
    }
    GlassPage(overlay = { page ->
        GlassTopBar(group?.title ?: "群聊", page, subtitle = "${participating.size} 位 TA · 一起聊天",
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
            trailing = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassIconButton(Icons.Rounded.Book, "群内小本本", { problem = null; memoriesOpen = true }, page)
                // 不用人先开口：点一下，几位 TA 自己轮流接几句。
                GlassIconButton(Icons.Rounded.Forum, "让他们自己聊", { act { c.groupChat.selfTalk(groupId, 3) } }, page)
                GlassIconButton(Icons.Rounded.Groups, "群聊成员", { details = true }, page)
            } },
            onTitleClick = { details = true })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp).onSizeChanged { composerHeight = it.height },
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
            // 录音中 / 转文字中 / 刚刚失败的那句话：和单聊一个样式、同一套状态（见 VoiceHold.kt）。
            if (recording || transcribingVoice || voiceHint != null) {
                RecordingPill(
                    backdrop = page,
                    recording = recording,
                    ms = recordedMs,
                    level = loudness,
                    target = holdTarget,
                    cancelling = cancelling,
                    hint = if (transcribingVoice) "正在转成文字…" else voiceHint,
                )
            }
            if (mentions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mentions.forEach { id -> Chip("@${members.firstOrNull { it.companionId == id }?.name ?: "TA"} ×", false) { mentions = mentions - id } }
            }
            // 选了、还没发出去的图 —— **发之前得看得见自己选了什么**。
            if (pictures.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pictures.forEach { picture ->
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))) {
                        AsyncImage(
                            model = c.images.file(picture.file),
                            contentDescription = "群里要发的图",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { pictures = pictures - picture; c.appScope.launch { c.images.delete(listOf(picture.file)) } },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, contentDescription = "移除", tint = Color.White, modifier = Modifier.size(12.dp)) }
                    }
                }
            }
            // 「＋」展开的那层：图片 / 语音 / 点名都收进来，输入框才留得住宽度。
            // 「＋」展开：和单聊同一个面板（换行网格 + 内部滚动，见 ChatInputParts.kt）。
            if (attachOpen) AttachPanel(Modifier.padding(top = 2.dp, bottom = 2.dp)) {
                AttachTile(Icons.Rounded.AddPhotoAlternate, "图片") {
                    attachOpen = false
                    pickingPicture = true
                    picturePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                // 拍照：和「图片」并排；拍完先预览，确认才进待发送的图。
                AttachTile(Icons.Rounded.PhotoCamera, "拍照") {
                    attachOpen = false
                    photoProblem = null
                    photo.take()
                }
                // 按住说话：和输入框上是同一套（长按记录，左滑转文字 / 右滑直接发 / 上滑取消）。
                HoldTile(
                    recording = recording,
                    onTap = { attachOpen = false },
                    onStart = ::startVoice,
                    onMove = { holdTarget = it; cancelling = it == null },
                    onEnd = ::finishVoice,
                )
                AttachTile(Icons.Rounded.AlternateEmail, "点名成员") {
                    attachOpen = false
                    picking = true
                }
            }
            GlassSurface(Modifier.fillMaxWidth(), backdrop = page, style = palette.card, shape = GlassShape.Rounded(26.dp),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)) {
                // 底对齐：单行时按钮槽 44dp 和输入框同样高（同一条中心线），多行时按钮贴底、不跟着漂。
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ＋：默认只有它、输入框、发送 —— 别的都收在展开的那层里。
                    // 视觉 34dp / 点击 44dp（和单聊同一套，见 ChatInputParts.kt）。
                    BarIconButton(
                        if (attachOpen) Icons.Rounded.Close else Icons.Rounded.Add,
                        if (attachOpen) "收起更多" else "更多",
                        onClick = { attachOpen = !attachOpen },
                        enabled = !recording,
                        tint = if (attachOpen) Color.White else palette.accentContent,
                        background = if (attachOpen) palette.accent else Color.Transparent,
                    )
                    Box(Modifier.weight(1f)) {
                        BasicTextField(draft, { draft = it.take(GroupChatRules.MAX_TEXT) }, modifier = Modifier.fillMaxWidth()
                            // 最小高度和按钮槽同高（44dp）：单行时文字中心和图标中心对齐。
                            .heightIn(min = 44.dp, max = 130.dp).focusRequester(focus).semantics { contentDescription = "群聊消息" },
                            textStyle = TextStyle(color = palette.content, fontSize = 16.sp), cursorBrush = SolidColor(palette.content),
                            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                                if (draft.isEmpty()) Text("说点什么…", color = palette.contentSecondary, fontSize = 16.sp)
                                inner()
                            } })
                        // **长按输入框**：和单聊同一个层 —— 左滑转文字、右滑直接发、上滑取消。
                        if (draft.isEmpty() && !busy && loaded) {
                            HoldToTalkLayer(
                                onTap = {
                                    focus.requestFocus()
                                    keyboard?.show()
                                },
                                onStart = ::startVoice,
                                onMove = { holdTarget = it; cancelling = it == null },
                                onEnd = ::finishVoice,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                    }
                    if (busy) {
                        BarIconButton(
                            Icons.Rounded.Stop,
                            "停止群聊回复",
                            onClick = { act { c.groupChat.stop(groupId) } },
                            tint = palette.content,
                            background = palette.content.copy(alpha = 0.1f),
                        )
                    } else {
                        BarIconButton(
                            Icons.AutoMirrored.Rounded.Send,
                            "发送群聊消息",
                            onClick = {
                                // 只发图不写字也算一条 —— 群里发张图不用配文字。
                                if ((draft.isNotBlank() || pictures.isNotEmpty()) && !posting && loaded) act {
                                    posting = true
                                    val sentPictures = pictures
                                    try {
                                        c.groupChat.send(groupId, draft, mentions, sentPictures)
                                        draft = ""; mentions = emptyList(); pictures = emptyList()
                                        if (rows.isNotEmpty()) list.scrollToItem(rows.lastIndex)
                                    } finally { posting = false }
                                }
                            },
                            enabled = !posting,
                            background = palette.accent.copy(alpha = if (draft.isNotBlank() || pictures.isNotEmpty()) 1f else 0.35f),
                        )
                    }
                }
            }
        }
    }) {
        if (group == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在读取群聊，或这个群已被删除", color = palette.contentSecondary)
        } else CompositionLocalProvider(LocalBubblePadding provides (settings.bubblePaddingX to settings.bubblePaddingY),
            LocalBubbleDecoration provides settings.bubbleDecoration, LocalMessageSpacing provides settings.messageSpacing) {
            LazyColumn(Modifier.fillMaxSize().imePadding(), state = list,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top + 12.dp, bottom = bottom + 28.dp),
                verticalArrangement = Arrangement.spacedBy(BubbleSpacing.normalized(settings.messageSpacing).dp)) {
                if (rows.isEmpty()) item {
                    GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
                        Text("大家都在这里了。说句话，或者点 @ 请一位伙伴接话。", color = palette.contentSecondary, fontSize = 14.sp)
                    }
                }
                items(rows, key = { it.id }) { row ->
                    val mine = row.role == "user"
                    val text = live[row.id] ?: row.content
                    val waiting = progress[row.id]?.label(tickNanos) ?: "正在连接…"
                    val ahead = rows.firstOrNull { it.status == "running" && it.turnId == row.turnId }?.speakerName
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Top) {
                        if (!mine) { Avatar(row.speakerAvatar, row.speakerEmoji ?: avatarLetter(row.speakerName, "TA"), 34.dp); Spacer(Modifier.width(8.dp)) }
                        Column(Modifier.widthIn(max = bubbleWidth), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            Text(row.speakerName.ifBlank { if (mine) "我" else "TA" }, color = palette.contentSecondary, fontSize = 11.sp)
                            if (mine && GroupChatRules.ids(row.mentions).isNotEmpty()) Text(GroupChatRules.ids(row.mentions)
                                .joinToString(" ") { "@${members.firstOrNull { m -> m.companionId == it }?.name ?: "TA"}" }, color = palette.contentSecondary, fontSize = 11.sp)
                            // 语音：能播、看得见多长；说了什么由仓库那边转成文字填进 content。
                            val clip = remember(row.audio) { MessageAudios.decode(row.audio) }
                            if (clip != null) Row(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(palette.content.copy(alpha = 0.07f))
                                    .clickable { playVoice(clip.file) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    if (playing == clip.file) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                    contentDescription = "播放语音",
                                    tint = palette.accentContent,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    (clip.ms / 1000).coerceAtLeast(1).toString() + " 秒",
                                    color = palette.content, fontSize = 13.sp,
                                )
                            }
                            val photos = remember(row.images) { MessageImages.decode(row.images) }
                            if (text.isNotBlank() || photos.isEmpty() || row.status in setOf("pending", "running")) ChatBubbleSurface(mine = mine, theme = if (mine) settings.myBubbleTheme else settings.taBubbleThemes[row.speakerId.toString()] ?: "glass",
                                background = settings.bubbleBackgrounds[if (mine) settings.myBubbleTheme else settings.taBubbleThemes[row.speakerId.toString()] ?: "glass"],
                                modifier = Modifier.widthIn(max = bubbleWidth).combinedClickable(onClick = {}, onLongClick = {
                                    if (text.isNotBlank()) {
                                        if (GroupMemoryRules.canRemember(row)) actionMessageId = row.id else selectedText = text
                                    }
                                })) { ink ->
                                MessageText(text.ifEmpty { when (row.status) {
                                    "pending" -> ahead?.let { "等${it}接话后轮到我…" } ?: "等会儿接话…"
                                    "running" -> waiting
                                    else -> "这次没有回复"
                                } }, ink,
                                    TextStyle(fontSize = 16.sp, lineHeight = 23.sp))
                            }
                            photos.forEach { photo -> GroupPhotoCard(photo, bubbleWidth, onOpenImage) }
                            if (row.status == "running" && text.isNotEmpty()) Text(waiting, color = palette.contentSecondary, fontSize = 11.sp)
                            val toolTrace = remember(row.toolTrace) { GroupToolRules.decode(row.toolTrace) }
                            if (toolTrace.records.isNotEmpty()) TextButton({ toolMessageId = row.id }) {
                                Text("工具记录 · ${toolTrace.records.size}", color = palette.accentContent, fontSize = 12.sp)
                            }
                            if (toolTrace.notice.isNotEmpty()) Text(toolTrace.notice, color = palette.contentSecondary, fontSize = 12.sp)
                            row.error?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                            if (!busy && row.speakerId in availableMembers && GroupChatRules.retryable(row) && row.turnId == rows.mapNotNull { it.turnId }.maxOrNull()) {
                                TextButton({ act { c.groupChat.retry(row.id) } }) { Text("请${row.speakerName}再试一次", color = palette.accentContent, fontSize = 12.sp) }
                            }
                        }
                        // 群聊里「我」的头像：**这个群专属的 > 全局默认**，不跟着谁发言变（见 UserFaces）。
                        if (mine && settings.chatAvatars) { Spacer(Modifier.width(8.dp)); Avatar(com.cleo.cleos.data.UserFaces.forGroup(group, settings), avatarLetter(settings.userName, "我"), 34.dp) }
                    }
                }
            }
        }
    }
    myFaceCrop?.let { uri ->
        com.cleo.cleos.ui.home.AvatarCropDialog(
            uri = uri,
            onDismiss = { myFaceCrop = null },
            onCropped = { bmp ->
                myFaceCrop = null
                val old = group?.userAvatar
                scope.launch {
                    val file = runCatching { c.images.save(bmp, prefix = "me-group-") }.getOrNull() ?: return@launch
                    c.groupChat.setMyAvatar(groupId, file)
                    if (!old.isNullOrBlank()) runCatching { c.images.delete(listOf(old)) }
                }
            },
        )
    }

    group?.let { saved ->
        if (picking || details) GroupMembersDialog(saved, members, active, if (picking) mentions else null,
            if (picking) { value -> mentions = value; picking = false } else null,
            { picking = false; details = false }, { name -> act { c.groupChat.rename(groupId, name); details = false } },
            canManage = !busy && members.isNotEmpty() && active.isNotEmpty(),
            onManage = { problem = null; details = false; editingMembers = true },
            myAvatar = saved.userAvatar,
            defaultAvatar = settings.userAvatar,
            onPickMyAvatar = { myFacePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onClearMyAvatar = {
                val old = saved.userAvatar
                act {
                    c.groupChat.setMyAvatar(groupId, null)
                    if (!old.isNullOrBlank()) runCatching { c.images.delete(listOf(old)) }
                }
            })
        if (editingMembers) EditGroupMembersDialog(saved, members, tas, savingMembers || busy, problem,
            onDismiss = { if (!savingMembers) { editingMembers = false; problem = null } },
            onSave = { value -> act {
                savingMembers = true
                try {
                    c.groupChat.updateMembers(groupId, value)
                    mentions = mentions.filter { it in value }
                    editingMembers = false
                } finally { savingMembers = false }
            } })
    }
    actionMessageId?.let { id -> rows.firstOrNull { it.id == id }?.let { row ->
        GroupMessageActionsDialog(row, !busy, { actionMessageId = null },
            onSelect = { actionMessageId = null; selectedText = row.content },
            onRemember = {
                actionMessageId = null; problem = null
                editingMemoryId = notes.firstOrNull { it.sourceMessageId == row.id }?.id
                memorySourceId = row.id; memoriesOpen = true; memoryEditor = true
            })
    } }
    toolMessageId?.let { id -> rows.firstOrNull { it.id == id }?.let { row ->
        GroupToolsDialog(row, { toolMessageId = null })
    } }
    if (memoriesOpen && !memoryEditor && deletingMemoryId == null) GroupMemoriesDialog(notes, rows.associateBy { it.id }, busy,
        problem, memorySearch, { memorySearch = it }, { memoriesOpen = false; problem = null },
        onNew = { problem = null; editingMemoryId = null; memorySourceId = null; memoryEditor = true },
        onEdit = { note -> problem = null; editingMemoryId = note.id; memorySourceId = note.sourceMessageId; memoryEditor = true },
        onDelete = { note -> problem = null; deletingMemoryId = note.id },
        onSource = { row -> selectedText = "${row.speakerName.ifBlank { if (row.role == "user") "我" else "TA" }}的原消息\n\n${row.content}" })
    if (memoryEditor) EditGroupMemoryDialog(notes.firstOrNull { it.id == editingMemoryId }, rows.firstOrNull { it.id == memorySourceId },
        savingMemory || busy, problem,
        onDismiss = { if (!savingMemory) { memoryEditor = false; problem = null } },
        onSave = { title, content -> act {
            savingMemory = true
            try {
                c.groupChat.saveMemory(groupId, editingMemoryId, title, content, memorySourceId, generation)
                memoryEditor = false
            } finally { savingMemory = false }
        } })
    deletingMemoryId?.let { id -> notes.firstOrNull { it.id == id }?.let { note ->
        DeleteGroupMemoryDialog(note, savingMemory || busy, problem,
            onDismiss = { if (!savingMemory) { deletingMemoryId = null; problem = null } },
            onDelete = { act {
                savingMemory = true
                try { c.groupChat.deleteMemory(groupId, id, generation); deletingMemoryId = null }
                finally { savingMemory = false }
            } })
    } }
    selectedText?.let { SelectMessageTextDialog(it, TextStyle(fontSize = 16.sp, lineHeight = 23.sp)) { selectedText = null } }
}

/** 「＋」面板里的一项：图标 + 文字，点一下就走。 */
@Composable
private fun PanelAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.content.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(18.dp))
        Text(label, color = palette.content, fontSize = 14.sp)
    }
}
