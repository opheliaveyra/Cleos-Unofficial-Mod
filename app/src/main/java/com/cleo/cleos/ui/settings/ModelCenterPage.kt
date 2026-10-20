package com.cleo.cleos.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.Capability
import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.ModelConfig
import com.cleo.cleos.data.ModelRegistryData
import com.cleo.cleos.data.Provider
import com.cleo.cleos.ui.theme.CleosIcon
import com.cleo.cleos.glass.LocalGlassPalette

/** 模型中心内部要去哪一页。不再拆成四个设置入口。 */
private sealed interface CenterView {
    data object Home : CenterView
    data class ProviderDetail(val id: String) : CenterView
    data class ConfigDetail(val id: String) : CenterView
    data object Assign : CenterView
}

/**
 * 模型中心：原来「模型」「服务商」「模型配置」三个入口合成这一个。
 *
 * 首页只放三件每天都要做的事：现在用哪个模型、服务商和它下面的模型、给功能分模型。
 * 参数、能力测试这些都往详情页里收，不铺在首页。
 */
@Composable
internal fun ModelCenterPage(vm: SettingsViewModel, onOpenSpokenModel: () -> Unit = {}) {
    var view by remember { mutableStateOf<CenterView>(CenterView.Home) }
    when (val v = view) {
        CenterView.Home -> ModelCenterHome(
            vm,
            onProvider = { view = CenterView.ProviderDetail(it) },
            onAssign = { view = CenterView.Assign },
        )
        is CenterView.ProviderDetail -> ProviderDetailPage(
            vm, v.id,
            onBack = { view = CenterView.Home },
            onConfig = { view = CenterView.ConfigDetail(it) },
        )
        is CenterView.ConfigDetail -> ConfigDetailPage(
            vm, v.id,
            onBack = { view = CenterView.Home },
        )
        CenterView.Assign -> AssignModelsPage(vm, onBack = { view = CenterView.Home }, onOpenSpokenModel = onOpenSpokenModel)
    }
}

// ---------------------------------------------------------------- 首页

@Composable
private fun ModelCenterHome(vm: SettingsViewModel, onProvider: (String) -> Unit, onAssign: () -> Unit) {
    val palette = LocalGlassPalette.current
    val data by vm.registry.collectAsStateWithLifecycle()
    var switching by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    MessageLine(vm.registryMessage)

    // ① 现在用的模型：一步就能换，不用知道它是哪一层的。
    Section("现在用的模型") {
        val current = data.configs.firstOrNull { it.id == data.defaultConfigId }
        val provider = current?.let { c -> data.providers.firstOrNull { it.id == c.providerId } }
        if (current == null) {
            Text(
                if (data.configs.isEmpty()) "还没有模型 —— 往下加一个服务商，再挑一个型号。"
                else "全局默认指的那条不在了，重新选一个。",
                color = palette.error, fontSize = 13.sp,
            )
        } else {
            Text(current.label, color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(
                current.model + " · " + (provider?.name?.ifBlank { provider.baseUrl } ?: "服务商不在了"),
                color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("换一个", selected = false) { switching = true }
            Chip("给功能分模型", selected = false) { onAssign() }
        }
    }

    // ② 服务商 → 模型：按家分组，不再把所有模型平铺成一条长列表。
    Section("服务商与模型") {
        if (data.providers.isEmpty()) {
            Text("还没有服务商。加一个，填上接口地址和 Key。", color = palette.contentSecondary, fontSize = 13.sp)
        }
        data.providers.forEach { p ->
            val models = data.configs.filter { it.providerId == p.id }
            ProviderRow(data, p, models, onClick = { onProvider(p.id) })
            RowDivider(inset = 0.dp)
        }
        Spacer(Modifier.height(6.dp))
        AddButton("＋ 添加服务商") { if (!vm.registryBusy) adding = true }
    }

    // ③ 按用途分配：9 个功能 + 语音都在这一页。
    Section("按用途") {
        Text("聊天、总结、看图、语音这些分别用哪个模型。没单独选的跟随「现在用的模型」。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(4.dp))
        FeatureSummary(data) { onAssign() }
    }

    if (switching) {
        PickConfigDialog(
            data = data,
            title = "现在用哪个模型",
            currentId = data.defaultConfigId,
            onDismiss = { switching = false },
        ) { id -> vm.setDefaultConfig(id); switching = false }
    }
    if (adding) {
        ProviderDialog(
            provider = null,
            onDismiss = { adding = false },
            onSave = { name, url, kind, key -> vm.addProvider(name, url, kind, key); adding = false },
        )
    }
}

@Composable
private fun ProviderRow(data: ModelRegistryData, provider: Provider, models: List<ModelConfig>, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(provider.name.ifBlank { provider.baseUrl }, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                provider.baseUrl + " · " + models.size + " 个模型",
                color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        CleosIcon(Icons.Rounded.Tune, contentDescription = null, tint = palette.contentSecondary, modifier = Modifier.width(18.dp))
    }
}

@Composable
private fun FeatureSummary(data: ModelRegistryData, onOpen: () -> Unit) {
    val palette = LocalGlassPalette.current
    val bound = Feature.entries.count { data.routes[it.name]?.configId?.isNotBlank() == true }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (bound == 0) "全部跟随默认" else "$bound 个功能单独选了模型",
            color = palette.content, fontSize = 14.sp,
        )
        Spacer(Modifier.weight(1f))
        Text("去看看", color = palette.accentContent, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- 服务商详情

@Composable
private fun ProviderDetailPage(vm: SettingsViewModel, providerId: String, onBack: () -> Unit, onConfig: (String) -> Unit) {
    val palette = LocalGlassPalette.current
    val data by vm.registry.collectAsStateWithLifecycle()
    val provider = data.providers.firstOrNull { it.id == providerId }
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    BackBar(provider?.name?.ifBlank { provider?.baseUrl.orEmpty() } ?: "服务商", onBack)

    if (provider == null) {
        Section(null) { Text("这个服务商已经不在了。", color = palette.error, fontSize = 14.sp) }
        return
    }
    val models = data.configs.filter { it.providerId == provider.id }

    Section("连接") {
        Text(provider.baseUrl, color = palette.content, fontSize = 14.sp)
        Text("接口类型：" + provider.kind.label, color = palette.contentSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Busy("测试连接", vm.testing == provider.id) { vm.testProvider(provider) }
            TextButton(onClick = { editing = true }) { Text("改地址和 Key", fontSize = 12.sp) }
        }
        vm.testResult?.takeIf { it.first == provider.id }?.let { (_, said) -> MessageLine(said) }
    }

    Section("这家下面的模型") {
        if (models.isEmpty()) Text("还没有模型。点下面加一个。", color = palette.contentSecondary, fontSize = 13.sp)
        models.forEach { c ->
            val isDefault = data.defaultConfigId == c.id
            val usedBy = Feature.entries.filter { data.routes[it.name]?.configId == c.id }
            Column(Modifier.fillMaxWidth().clickable { onConfig(c.id) }.padding(vertical = 8.dp)) {
                Text(
                    c.label + if (isDefault) "（现在用的）" else "",
                    color = palette.content, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    buildString {
                        append(c.model)
                        append(" · ")
                        append(if (c.capabilities.isEmpty()) "能力没验过" else c.capabilities.joinToString("、") { it.label })
                        if (usedBy.isNotEmpty()) append(" · ").append(usedBy.joinToString("、") { it.label }).append(" 在用")
                    },
                    color = palette.contentSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            RowDivider(inset = 0.dp)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddButton("＋ 加一个模型") { if (!vm.registryBusy) adding = true }
            AddButton("＋ 从列表选") { if (!vm.registryBusy) vm.openBatch(provider.id) }
        }
    }

    if (editing) {
        ProviderDialog(
            provider = provider,
            onDismiss = { editing = false },
            onSave = { name, url, kind, key -> vm.updateProvider(provider.id, name, url, kind, key); editing = false },
        )
    }
    if (adding) {
        ConfigDialog(
            data = data,
            config = null,
            onDismiss = { adding = false },
            onSave = { pid, model, name, t, mt -> vm.addConfig(pid, model, name, t, mt); adding = false },
            onSetDefault = {},
            onDelete = {},
            isDefault = false,
            affected = emptyList(),
        )
    }
}

// ---------------------------------------------------------------- 模型详情

@Composable
private fun ConfigDetailPage(vm: SettingsViewModel, configId: String, onBack: () -> Unit) {
    val palette = LocalGlassPalette.current
    val data by vm.registry.collectAsStateWithLifecycle()
    val config = data.configs.firstOrNull { it.id == configId }
    var editing by remember { mutableStateOf(false) }

    BackBar(config?.label ?: "模型", onBack)
    if (config == null) {
        Section(null) { Text("这条模型已经不在了。", color = palette.error, fontSize = 14.sp) }
        return
    }
    val provider = data.providers.firstOrNull { it.id == config.providerId }
    val isDefault = data.defaultConfigId == config.id
    val usedBy = Feature.entries.filter { data.routes[it.name]?.configId == config.id }

    Section("基本信息") {
        Text(config.model, color = palette.content, fontSize = 15.sp)
        Text("服务商：" + (provider?.name?.ifBlank { provider.baseUrl } ?: "不在了"), color = palette.contentSecondary, fontSize = 12.sp)
        Text(
            "参数：温度 " + (config.temperature?.toString() ?: "默认") + " · 上限 " + (config.maxTokens?.toString() ?: "默认"),
            color = palette.contentSecondary, fontSize = 12.sp,
        )
        if (usedBy.isNotEmpty()) {
            Text("正在被这些用：" + usedBy.joinToString("、") { it.label }, color = palette.contentSecondary, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isDefault) Chip("设为现在用的", selected = false) { vm.setDefaultConfig(config.id) }
            Chip("改参数", selected = false) { editing = true }
        }
    }

    Section("能力") {
        if (config.capabilities.isEmpty()) {
            Text("还没验证过这个模型能不能看图 / 看视频。下面测一下。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        } else {
            Text("已验证：" + config.capabilities.joinToString("、") { it.label }, color = palette.content, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Busy("测看图", vm.testing == config.id) { vm.testConfig(config, Capability.Image) }
            Busy("测看视频", vm.testing == config.id) { vm.testConfig(config, Capability.Video) }
        }
        vm.capabilityResult?.takeIf { it.first == config.id }?.let { (_, r) -> ProbeResultLine(r) }
        Text(
            "测试没过不等于它一定不支持，也可能是这张图、这个网络的问题 —— 结果只当参考。",
            color = palette.contentSecondary, fontSize = 11.sp, lineHeight = 15.sp,
        )
    }

    if (editing) {
        ConfigDialog(
            data = data,
            config = config,
            onDismiss = { editing = false },
            onSave = { pid, model, name, t, mt -> vm.updateConfig(config.id, pid, model, name, t, mt); editing = false },
            onSetDefault = { vm.setDefaultConfig(config.id) },
            onDelete = { vm.removeConfig(config.id); editing = false },
            isDefault = isDefault,
            affected = usedBy,
        )
    }
}

// ---------------------------------------------------------------- 按用途分配

@Composable
private fun AssignModelsPage(vm: SettingsViewModel, onBack: () -> Unit, onOpenSpokenModel: () -> Unit) {
    val palette = LocalGlassPalette.current
    val data by vm.registry.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Feature?>(null) }

    BackBar("按用途选模型", onBack)
    PageNote("每个功能可以单独挑一个模型；不挑就跟随「现在用的模型」。")

    Section("功能") {
        Feature.entries.forEach { f ->
            val boundId = data.routes[f.name]?.configId.orEmpty()
            val bound = data.configs.firstOrNull { it.id == boundId }
            Column(
                Modifier.fillMaxWidth().clickable {
                    // 语音这一行特殊：老结构还留着，点进去是它自己的编辑页。
                    if (f == Feature.Spoken) onOpenSpokenModel() else picking = f
                }.padding(vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, color = palette.content, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (bound != null) bound.label else "跟随默认",
                        color = if (bound != null) palette.accentContent else palette.contentSecondary,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    if (f == Feature.Spoken && vm.spokenOn &&
                        vm.spoken.baseUrl.isNotBlank() && vm.spoken.model.isNotBlank()
                    ) "这个 TA 还开着一条独立的语音模型，优先用它 —— 点进来改或关掉"
                    else f.hint,
                    color = palette.contentSecondary, fontSize = 11.sp, lineHeight = 15.sp,
                )
            }
            RowDivider(inset = 0.dp)
        }
    }

    Section("说明") {
        Text(
            "「所有 TA 共用」和「当前 TA 专属」还是分开的：这一页管的是所有 TA 共用的那份；" +
                "某个 TA 自己的设置仍然在它自己的页面里，不会因为这里换了就跟着变。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }

    picking?.let { f ->
        PickConfigDialog(
            data = data,
            title = "「" + f.label + "」用哪个模型",
            currentId = data.routes[f.name]?.configId.orEmpty(),
            allowFollow = true,
            onDismiss = { picking = null },
        ) { id ->
            vm.bindFeature(f, id)
            picking = null
        }
    }
}

// ---------------------------------------------------------------- 小件

@Composable
private fun BackBar(title: String, onBack: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable(onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CleosIcon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = palette.content, modifier = Modifier.width(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PickConfigDialog(
    data: ModelRegistryData,
    title: String,
    currentId: String,
    allowFollow: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val palette = LocalGlassPalette.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (allowFollow) {
                    ListRow("跟随默认", "不单独指定", chosen = currentId.isBlank()) { onPick("") }
                }
                if (data.configs.isEmpty()) {
                    Text("还没有模型。先回去加一个服务商。", color = palette.contentSecondary, fontSize = 13.sp)
                }
                data.configs.forEach { c ->
                    val p = data.providers.firstOrNull { it.id == c.providerId }
                    ListRow(c.label, c.model + " · " + (p?.name?.ifBlank { p.baseUrl } ?: "服务商不在了"), chosen = c.id == currentId) {
                        onPick(c.id)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
