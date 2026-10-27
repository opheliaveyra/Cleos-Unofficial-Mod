package com.cleo.cleos.ui.settings

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.ApiPresets
import com.cleo.cleos.data.needs
import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.db.FeatureModel
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.avatarLetter
import com.cleo.cleos.ui.home.AvatarCropDialog

/**
 * The TA's picture and name, the start of their persona (written on a page of its own,
 * PersonaScreen), and, when there is more than one TA, deleting this one.
 */
@Composable
internal fun ProfilePage(vm: SettingsViewModel, onOpenPersona: () -> Unit, onLeave: () -> Unit) {
    val palette = LocalGlassPalette.current
    val companions by vm.companions.collectAsStateWithLifecycle()
    val ta = companions.firstOrNull { it.id == vm.companionId }
    val name = vm.aiName.trim().ifEmpty { "这个 TA" }
    var cropping by remember { mutableStateOf<Uri?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) cropping = uri }
    // 「我」在这个角色这儿的头像：和上面 TA 自己的头像是两件事（见 UserFaces）。
    var croppingMine by remember { mutableStateOf<Uri?>(null) }
    val myPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) croppingMine = uri }

    Section("头像和名字") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(ta?.avatar, ta?.avatarEmoji ?: avatarLetter(vm.aiName, "TA"), 72.dp)
            Spacer(Modifier.width(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val has = ta?.avatar != null || ta?.avatarEmoji != null
                Chip(if (has) "换一张" else "换头像", selected = false) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (has) Chip("用回默认", selected = false) { vm.clearAvatar() }
            }
        }
        Field("名字", vm.aiName, { vm.aiName = it })
    }

    // 角色专属的「我」：**只影响和这个角色的聊天**，不碰全局和其他角色。
    Section("我在这个角色这儿的头像") {
        Text(
            "只影响和${name}的聊天；别的角色、以及群聊各用各的。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp,
        )
        // 并排预览：「我的头像 + 角色头像」，和聊天里看到的一对一样。
        // 换的时候能直接看出配不配，而不是先存下来再回聊天里比。
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val mine = vm.myAvatarForCompanion()
            Avatar(mine ?: vm.myAvatarDefault, avatarLetter(vm.userName, "我"), 56.dp)
            Text("＋", color = palette.contentSecondary, fontSize = 14.sp)
            Avatar(ta?.avatar, ta?.avatarEmoji ?: avatarLetter(vm.aiName, "TA"), 56.dp)
            Spacer(Modifier.width(2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (mine == null) "上传" else "更换", selected = false) {
                    myPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (mine != null) Chip("用回全局默认", selected = false) { vm.clearMyAvatarForCompanion() }
            }
        }
        Text(
            if (vm.myAvatarForCompanion() == null) "现在用的是全局默认头像" else "这是这个角色专属的",
            color = palette.contentSecondary, fontSize = 11.sp,
        )
    }

    Section("性格") {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(interactionSource = null, indication = null, onClickLabel = "打开", onClick = onOpenPersona),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    vm.persona.take(PERSONA_PREVIEW).replace('\n', ' ').trim().ifEmpty { "想让 TA 怎样说话、记得什么，都写在这里。可以不写。" },
                    color = if (vm.persona.isBlank()) palette.contentSecondary else palette.content,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (vm.persona.isEmpty()) "" else "${vm.persona.length} / $PERSONA_LIMIT 字 · ") + "点开单独一页写",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.contentSecondary)
        }
    }

    if (vm.companionCount > 1) {
        Row(Modifier.padding(horizontal = 2.dp)) {
            Chip("删除$name", selected = false) { confirmDelete = true }
        }
    }

    cropping?.let { uri ->
        AvatarCropDialog(
            uri = uri,
            onDismiss = { cropping = null },
            onCropped = { picture ->
                cropping = null
                vm.setAvatar(picture)
            },
        )
    }

    croppingMine?.let { uri ->
        AvatarCropDialog(
            uri = uri,
            onDismiss = { croppingMine = null },
            onCropped = { picture ->
                croppingMine = null
                vm.setMyAvatarForCompanion(picture)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除$name？") },
            text = { Text("和${name}的所有对话、信、记忆、收藏，以及${name}写的日记会一起删掉，删了找不回来。你自己的日记和待办不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCompanion(onLeave)
                }) { Text("删除", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/**
 * Which service the TA talks through, its key and its model; and, if wanted, another model for
 * the words that are heard: on the phone, and answering a voice message.
 */
@Composable
internal fun ModelPage(vm: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val palette = LocalGlassPalette.current
    // 转屏、折叠屏展开、切深浅色这些「配置变化」也会走到 onDispose，而 ViewModel 活了下来 ——
    // 于是刚粘进去的接口地址和 Key 会被清回已保存的值，人以为自己没保存上。
    val activity = LocalActivity.current
    androidx.compose.runtime.DisposableEffect(vm) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                vm.chat.discardDraft()
                vm.spoken.discardDraft()
            }
        }
    }

    // ---- 聊天（也就是全局默认）走哪条配置 ----
    val data by vm.registry.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    val chatConfig = data.configs.firstOrNull { it.id == data.defaultConfigId }
    val chatProvider = chatConfig?.let { c -> data.providers.firstOrNull { it.id == c.providerId } }
    Section("聊天用哪个 · 所有 TA 共用") {
        Text(
            "这一条是**所有 TA 共用**的：换个 TA 不会换模型。它同时是「全局默认」—— 下面没单独绑的功能也跟着它。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        when {
            data.configs.isEmpty() ->
                Text("还没有模型配置 —— 先去「模型配置」加一条。", color = palette.error, fontSize = 13.sp)
            chatConfig == null ->
                Text("全局默认指着的那条配置已经不在了，重新指一个。", color = palette.error, fontSize = 13.sp)
            else -> {
                Text(chatConfig.label, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${chatConfig.model} · ${chatProvider?.name?.ifBlank { chatProvider.baseUrl } ?: "服务商不在了"}",
                    color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("换一条", selected = false) { choosing = true }
            Chip("管理模型配置", selected = false) { onOpen(SettingsPage.ModelConfigs) }
        }
    }

    // 换全局默认：列的就是三层里那些「模型配置」，不再有"自己填地址"这条主路径。
    if (choosing) Dialog(onDismissRequest = { choosing = false }) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            style = palette.card,
            shape = GlassShape.Rounded(28.dp),
            contentPadding = PaddingValues(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("聊天用哪条配置", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "没单独绑的功能都跟着它。要加新的一条，去「模型配置」。",
                    color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                )
                if (data.configs.isEmpty()) Text("还没有模型配置。", color = palette.contentSecondary, fontSize = 14.sp)
                data.configs.forEach { c ->
                    val p = data.providers.firstOrNull { it.id == c.providerId }
                    ListRow(
                        title = c.label,
                        detail = "${c.model} · ${p?.name?.ifBlank { p.baseUrl } ?: "服务商不在了"}",
                        chosen = c.id == data.defaultConfigId,
                    ) { vm.setDefaultConfig(c.id); choosing = false }
                }
                TextButton(onClick = { choosing = false }) { Text("关闭") }
            }
        }
    }

    // ---- 说的和写的可以不是同一条 ----
    ListCard {
        ExplainedSwitch(
            "打电话、发语音时换个模型",
            "电话里和回你语音时，用另一个模型",
            "打开后，打电话时 TA 说的话，和你发语音过去后 TA 回的那一条，由下面这个模型来写，比如想让 Claude 来说。" +
                "平时打字、主动找你、写信、前情提要还是用上面那个。TA 自己想发的语音条，话在打字时就写好了，所以也还是上面那个。",
            vm.spokenOn,
        ) { vm.spokenOn = it }
    }

    if (vm.spokenOn) {
        Section("电话和语音用的模型 · 这个 TA 的") {
            ModelProfilesEditor(vm.spoken)
            ServiceChips(vm.spoken)
            ConnectionFields(vm.spoken)
            if (vm.spoken.baseUrl.isBlank() || vm.spoken.model.isBlank()) {
                Text("地址和模型都填好才会换，之前还用上面那个。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }

    Section("按功能换模型 · 所有 TA 共用") {
        Text(
            "**也是所有 TA 共用**：改一次，每个 TA 都按这个来。不同活儿值得用不同档次的模型：" +
                "**总结**、**朋友圈**这种是它自己在后台干的，用便宜的就够；**聊天**要好的；" +
                "**看视频**得专门挑一个真能读视频的。不绑就是跟着上面那个全局默认。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        FeatureModelRows(vm)
    }
}

/**
 * 每个功能用哪条模型配置 —— 就地看、就地改。
 *
 * 0.1.44 起，这一层的实现搬进了三层结构（[FeatureRoutesPage]），这里只留一个入口：
 * 同一件事在两处各写一套，正是玖玖说过的"存了之后就很难受"。
 */
@Composable
private fun FeatureModelRows(vm: SettingsViewModel) {
    FeatureRoutesPage(vm)
}

/** 选择弹窗里的一行：点一下选中，选中的那条打勾。 */
@Composable
private fun ListRow(title: String, detail: String, chosen: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.content.copy(alpha = if (chosen) 0.10f else 0.04f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.content, fontSize = 15.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
            Text(detail, color = palette.contentSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (chosen) Text("✓", color = palette.accentContent, fontSize = 16.sp)
    }
}

/** The services there are presets for, the one in use marked; anything else is 自定义. */
@Composable
private fun ServiceChips(fields: EndpointFields) {
    val palette = LocalGlassPalette.current
    // An address that is not one of the presets is somebody's own: a relay, a self-hosted
    // service, an address a friend handed over.
    val custom = fields.baseUrl.isNotBlank() && ApiPresets.at(fields.baseUrl) == null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ApiPresets.all.forEach { p ->
            Chip(p.name, selected = fields.baseUrl.trimEnd('/') == p.baseUrl) { fields.applyPreset(p) }
        }
        Chip("自定义 / 中转站", selected = custom) { fields.useCustom() }
    }
    Text(
        "上面是常见的几家。用中转站、自建的服务、别人给你的地址，就点「自定义 / 中转站」，" +
            "然后在下面把接口地址和模型名填上 —— 地址填到 /v1 或者不填都行。",
        color = palette.contentSecondary,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
}

/** Address, key and model, and the test that lists the address's models to pick from. */
@Composable
private fun ConnectionFields(fields: EndpointFields) {
    val palette = LocalGlassPalette.current
    val hasKey by fields.hasKey.collectAsStateWithLifecycle()
    var pickingModel by remember { mutableStateOf(false) }

    Field(
        "接口地址",
        fields.baseUrl,
        { fields.baseUrl = it },
        keyboardType = KeyboardType.Uri,
        hint = "https://…　中转站填它给你的那个地址",
    )
    OutlinedTextField(
        value = fields.keyInput,
        onValueChange = { fields.keyInput = it },
        label = { Text("API Key") },
        placeholder = { if (hasKey) Text("已保存（不再显示）；要换就重新填") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (fields.keyInput.isNotBlank()) Text("Key 将随当前配置一起保存", color = palette.contentSecondary, fontSize = 13.sp)
        if (hasKey && fields.keyInput.isBlank()) {
            Text("Key 已加密保存在这台手机上", color = palette.contentSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
    Field("模型", fields.model, { fields.model = it })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(if (fields.checking) "正在连接…" else "测试并列出模型", selected = false) { if (!fields.checking) fields.check() }
        if (!fields.models.isNullOrEmpty()) Chip("从列表里选", selected = false) { pickingModel = true }
        Chip(if (fields.profileBusy) "正在保存…" else "保存当前配置", selected = fields.hasDraftChanges) { if (!fields.profileBusy) fields.savingProfile = true }
    }
    if (fields.hasDraftChanges) Text("有未保存的修改，当前 TA 仍使用上面的配置。", color = palette.contentSecondary, fontSize = 12.sp)
    fields.checkResult?.let { Text(it, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp) }

    if (pickingModel) {
        AlertDialog(
            onDismissRequest = { pickingModel = false },
            title = { Text("选一个模型") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(fields.models.orEmpty()) { id ->
                        Text(
                            id,
                            fontSize = 15.sp,
                            fontWeight = if (id == fields.model) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    fields.model = id
                                    pickingModel = false
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingModel = false }) { Text("关闭") } },
        )
    }
}

/** How the TA goes about answering: thinking first, and coming to find the person on their own. */
@Composable
internal fun BehaviorPage(vm: SettingsViewModel) {
    Section("等我说完再回复") {
        Text("最后一条消息后，停顿多久再开始回复", color = LocalGlassPalette.current.contentSecondary, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.cleo.cleos.ai.ReplyWaitRules.OPTIONS.forEach { seconds ->
                Chip("${seconds} 秒", selected = vm.replyWaitSeconds == seconds) { vm.setReplyWait(seconds) }
            }
        }
        Text("连续发送文字、图片或表情包会重新计时。正在输入、录音或选附件时继续等，草稿最多额外等待 30 秒。语音转写完成后再回答；等待结束后才开始生成，还需要模型的响应时间。", color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
    ListCard {
        ExplainedSwitch(
            "深度思考",
            "回答前先想一想，更连贯，但更慢更费 token",
            "回答前先想一想：前后更连贯，推理也更好；每次要多等几秒，也多花一点 token。DeepSeek、智谱这类模型认这个开关，不认的会自动照常回复。" +
                "智谱 GLM-5.3 起的模型关不掉思考，关着时让它少想一点。",
            vm.deepThinking,
        ) { vm.setThinking(it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "主动找你",
            "想起什么会自己来找你，早晚也会打个招呼",
            "聊天时，TA 会给自己记下想过一阵再说的事（比如你去做饭了，过一会儿问问做得怎么样），到时候看看这之间聊了什么，" +
                "自己决定要不要找你；早上你醒了、晚上睡前，也可能来打个招呼。不会因为你没回、好久没聊来催你。",
            vm.proactive,
        ) { vm.setReachOut(it) }
    }
    Section("还有句话") {
        ExplainedSwitch("还有句话", "回复后，允许 TA 自己决定是否再补充一点",
            "每轮正常回复后只给一次机会，TA 也可以保持安静。你开始输入、录音、选附件或发送新消息时取消；补充不会接着触发补充。", vm.followUpEnabled) { vm.setFollowUp(it) }
        if (vm.followUpEnabled) {
            Text("回复结束后，等多久再考虑补充", color = LocalGlassPalette.current.contentSecondary, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.cleo.cleos.ai.FollowUpRules.OPTIONS.forEach { seconds ->
                    Chip(if (seconds == 30) "30 秒" else "${seconds / 60} 分钟", selected = vm.followUpDelaySeconds == seconds) { vm.setFollowUpDelay(seconds) }
                }
            }
            Text("默认关闭，每位 TA 分别设置。开启后会多一次模型请求，即使 TA 选择不说话也会消耗 token。后台执行可能稍晚，过时的机会会直接跳过。", color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
    FreeTopicSection(vm)
    FeedVisitSection(vm.companionId)
    if (vm.proactive || vm.freeTopicEnabled || vm.followUpEnabled) {
        Section("主动找你的时候") { ReachOutStatus(vm) }
    }
    WakeActivitySection(vm)
}
