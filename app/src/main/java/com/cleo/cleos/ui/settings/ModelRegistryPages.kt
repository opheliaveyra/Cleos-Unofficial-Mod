package com.cleo.cleos.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.ApiKind
import com.cleo.cleos.data.Capability
import com.cleo.cleos.data.FeatureRoute
import com.cleo.cleos.data.ModelConfig
import com.cleo.cleos.data.ModelRegistryData
import com.cleo.cleos.data.ParamOverrides
import com.cleo.cleos.data.Provider
import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.glass.LocalGlassPalette

/**
 * 三层结构的三个页面：**服务商 / 模型配置 / 功能绑定**。
 *
 * 为什么拆成三层（而不是"每个功能各填一个地址"）：同一个型号被好几个功能用到时，
 * 以前是**各抄一份**，改一处别处不知道 —— 玖玖说的"存了之后就很难受"就是这个。
 * 现在功能只存"指向哪条配置"，配一次，处处跟着变。
 */

/** 哪些功能绑在 [gone] 里的这些配置上 —— 删之前要说清楚会动到谁。 */
internal fun featuresUsing(data: ModelRegistryData, gone: Set<String>): List<Feature> =
    Feature.entries.filter { f -> data.routes[f.name]?.configId?.let { it.isNotEmpty() && it in gone } == true }

/** 一行字，说明这一页是干什么的。 */
@Composable
private fun PageNote(text: String) {
    Text(text, color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
}

/** 上一次动作的结果。**出错就用红的说出来**，别静默。 */
@Composable
private fun MessageLine(text: String?) {
    if (text.isNullOrBlank()) return
    Text(text, color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun RowTitle(title: String, detail: String) {
    val palette = LocalGlassPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun Busy(label: String, busy: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = !busy) {
        if (busy) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp) else Text(label, fontSize = 12.sp)
    }
}

/** 选一项（选模型、选服务商）时的一行：点整行就算选了。 */
@Composable
private fun OptionRow(title: String, detail: String, chosen: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (chosen) Text("已选", color = palette.accentContent, fontSize = 11.sp)
    }
}

// ---------------- ① 服务商 ----------------

/**
 * 服务商：接口地址、接口类型、以及那个地址的 Key（Key 不进这份配置，按地址存在加密存储里）。
 *
 * 「测试连接」**真发一条最小请求**，而不是"能列出模型就算通"：
 * 地址对、Key 少了一半的服务，列模型常常也是成功的，那样测出来的"通了"是假的。
 */
@Composable
internal fun ProvidersPage(vm: SettingsViewModel) {
    val data by vm.registry.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Provider?>(null) }
    var deleting by remember { mutableStateOf<Provider?>(null) }

    PageNote("服务商是「哪一家」：接口地址 + 接口类型。Key 按地址存，换型号不用重填；一家下面可以挂好几条模型配置。")
    MessageLine(vm.registryMessage)

    Section("已添加") {
        if (data.providers.isEmpty()) {
            Text("还没有。点下面的「添加服务商」，填上地址和 Key。", color = LocalGlassPalette.current.contentSecondary, fontSize = 13.sp)
        }
        data.providers.forEach { provider ->
            val configs = data.configs.filter { it.providerId == provider.id }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RowTitle(
                    provider.name.ifBlank { provider.baseUrl },
                    provider.baseUrl + " · " + provider.kind.label + " · " + configs.size + " 条模型配置",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Busy("测试连接", vm.testing == provider.id) { vm.testProvider(provider) }
                    TextButton(onClick = { editing = provider }) { Text("编辑", fontSize = 12.sp) }
                    TextButton(onClick = { deleting = provider }) { Text("删除", fontSize = 12.sp) }
                }
                vm.testResult?.takeIf { it.first == provider.id }?.let { (_, said) -> MessageLine(said) }
            }
            RowDivider(inset = 0.dp)
        }
        TextButton(onClick = { adding = true }, enabled = !vm.registryBusy) { Text("＋ 添加服务商") }
    }

    if (adding || editing != null) {
        val provider = editing
        ProviderDialog(
            provider = provider,
            onDismiss = { adding = false; editing = null },
            onSave = { name, url, kind, key ->
                if (provider == null) vm.addProvider(name, url, kind, key) else vm.updateProvider(provider.id, name, url, kind, key)
                adding = false
                editing = null
            },
        )
    }

    deleting?.let { provider ->
        val configs = data.configs.filter { it.providerId == provider.id }
        val affected = featuresUsing(data, configs.map { it.id }.toSet())
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${provider.name.ifBlank { provider.baseUrl }}」？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("它名下 ${configs.size} 条模型配置会一起删掉。")
                    if (affected.isEmpty()) {
                        Text("没有功能绑在它上面。")
                    } else {
                        Text("这些功能会回到「跟随全局默认」：${affected.joinToString("、") { it.label }}。")
                    }
                    Text("Key、聊天记录、TA 的资料都不动。")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.removeProvider(provider.id); deleting = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProviderDialog(
    provider: Provider?,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, kind: ApiKind, key: String) -> Unit,
) {
    val palette = LocalGlassPalette.current
    var name by remember { mutableStateOf(provider?.name.orEmpty()) }
    var url by remember { mutableStateOf(provider?.baseUrl.orEmpty()) }
    var kind by remember { mutableStateOf(provider?.kind ?: ApiKind.OpenAi) }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provider == null) "添加服务商" else "编辑服务商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("名字（小米、中转站…）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("接口类型", color = palette.contentSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApiKind.entries.forEach { k ->
                        Chip(k.label, selected = kind == k) { kind = k }
                    }
                }
                Text(
                    if (kind == ApiKind.OpenAi) "OpenAI 兼容是通的；另外两种能选，但发请求时会明说还没做。"
                    else "这个协议还没做 —— 保存后在聊天里发消息会明确告诉你，不会拿 OpenAI 的格式硬发。",
                    color = palette.contentSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                OutlinedTextField(
                    key, { key = it },
                    label = { Text(if (provider == null) "API Key" else "API Key（要换才填）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("Key 只写不读：存进加密存储，按地址归档。", color = palette.contentSecondary, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = { onSave(name, url, kind, key) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------- ② 模型配置 ----------------

/** 模型配置：某家服务商的某个型号 + 默认参数 + **验过的能力**。功能绑定指向它。 */
@Composable
internal fun ModelConfigsPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val data by vm.registry.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ModelConfig?>(null) }

    PageNote("一条模型配置 = 某家服务商的某个型号 + 默认参数。功能绑的是它：改这里，绑它的功能一起变。")
    MessageLine(vm.registryMessage)

    Section("已添加") {
        if (data.configs.isEmpty()) {
            Text("还没有。先加一条服务商，再来加型号。", color = palette.contentSecondary, fontSize = 13.sp)
        }
        data.configs.forEach { config ->
            val provider = data.providers.firstOrNull { it.id == config.providerId }
            val isDefault = data.defaultConfigId == config.id
            val usedBy = Feature.entries.filter { data.routes[it.name]?.configId == config.id }
            val caps = config.capabilities.joinToString("、") { it.label }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RowTitle(
                    config.label + if (isDefault) "（全局默认）" else "",
                    buildString {
                        append(config.model)
                        append(" · ")
                        append(provider?.name?.ifBlank { provider.baseUrl } ?: "服务商不在了")
                        append(if (caps.isEmpty()) " · 能力还没验过" else " · 验过：$caps")
                        if (usedBy.isNotEmpty()) append(" · ").append(usedBy.joinToString("、") { it.label }).append(" 在用")
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Busy("测看图", vm.testing == config.id) { vm.testConfig(config, Capability.Image) }
                    Busy("测看视频", vm.testing == config.id) { vm.testConfig(config, Capability.Video) }
                    TextButton(onClick = { editing = config }) { Text("编辑", fontSize = 12.sp) }
                }
                vm.testResult?.takeIf { it.first == config.id }?.let { (_, said) -> MessageLine(said) }
            }
            RowDivider(inset = 0.dp)
        }
        TextButton(
            onClick = { adding = true },
            enabled = !vm.registryBusy && data.providers.isNotEmpty(),
        ) { Text(if (data.providers.isEmpty()) "先加一个服务商" else "＋ 添加模型配置") }
    }

    if (adding || editing != null) {
        val config = editing
        ConfigDialog(
            data = data,
            config = config,
            onDismiss = { adding = false; editing = null },
            onSave = { providerId, model, name, temperature, maxTokens ->
                if (config == null) vm.addConfig(providerId, model, name, temperature, maxTokens)
                else vm.updateConfig(config.id, providerId, model, name, temperature, maxTokens)
                adding = false
                editing = null
            },
            onSetDefault = { vm.setDefaultConfig(config?.id.orEmpty()) },
            onDelete = { config?.let { vm.removeConfig(it.id) } },
            isDefault = config != null && data.defaultConfigId == config.id,
            affected = data.configs.firstOrNull { it.id == config?.id }
                ?.let { c -> featuresUsing(data, setOf(c.id)) }.orEmpty(),
        )
    }
}

@Composable
private fun ConfigDialog(
    data: ModelRegistryData,
    config: ModelConfig?,
    isDefault: Boolean,
    affected: List<Feature>,
    onDismiss: () -> Unit,
    onSave: (providerId: String, model: String, name: String, temperature: Float?, maxTokens: Int?) -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    var providerId by remember { mutableStateOf(config?.providerId ?: data.providers.firstOrNull()?.id.orEmpty()) }
    var model by remember { mutableStateOf(config?.model.orEmpty()) }
    var name by remember { mutableStateOf(config?.name.orEmpty()) }
    var temperature by remember { mutableStateOf(config?.temperature?.toString().orEmpty()) }
    var maxTokens by remember { mutableStateOf(config?.maxTokens?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (config == null) "添加模型配置" else "编辑模型配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务商", color = palette.contentSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    data.providers.forEach { p ->
                        Chip(p.name.ifBlank { p.baseUrl }, selected = providerId == p.id) { providerId = p.id }
                    }
                }
                OutlinedTextField(model, { model = it }, label = { Text("型号（deepseek-v4-flash…）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("给自己看的名字（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    temperature, { temperature = it.filter { ch -> ch.isDigit() || ch.code == 46 } },
                    label = { Text("默认温度（可空）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    maxTokens, { maxTokens = it.filter { ch -> ch.isDigit() } },
                    label = { Text("最长回复 token（可空）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (config != null) {
                    Text(
                        "能力靠「测一下」验出来，不能手填 —— 名字里带 VL 却只会看文字的见过太多了。",
                        color = palette.contentSecondary, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                    if (affected.isNotEmpty()) {
                        Text("现在有 ${affected.joinToString("、") { it.label }} 绑在它上面。", color = palette.contentSecondary, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!isDefault) TextButton(onClick = { onSetDefault(); onDismiss() }) { Text("设为全局默认", fontSize = 12.sp) }
                        TextButton(onClick = { onDelete(); onDismiss() }) { Text("删除", fontSize = 12.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = providerId.isNotBlank() && model.isNotBlank(), onClick = {
                onSave(providerId, model, name, temperature.toFloatOrNull(), maxTokens.toIntOrNull())
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------- ③ 功能绑定 ----------------

/** 每个功能选一条模型配置；没选就跟着全局默认。能力对不上当场红字，用之前就能看见。 */
@Composable
internal fun FeatureRoutesPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val data by vm.registry.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Feature?>(null) }
    var tuning by remember { mutableStateOf<Feature?>(null) }

    PageNote("每个功能挑一条模型配置，没挑的跟着全局默认（聊天在用那条）。挑了但能力对不上的，这里当场说出来。")
    MessageLine(vm.registryMessage)

    Feature.entries.forEach { feature ->
        val route = data.routes[feature.name]
        val bound = route?.configId.orEmpty()
        val config = data.configs.firstOrNull { it.id == bound.ifEmpty { data.defaultConfigId } }
        val provider = config?.let { c -> data.providers.firstOrNull { it.id == c.providerId } }
        val problem = vm.capabilityProblem(feature)
        ListCard {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(feature.label, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        bound.isEmpty() && config == null -> "跟随全局默认 · 还没配任何模型"
                        config == null -> "绑的那条配置已经不在了 —— 重新选一个"
                        bound.isEmpty() -> "跟随全局默认 · ${config.label}"
                        else -> "已绑定 · ${config.label}"
                    },
                    color = if (config == null) palette.error else palette.contentSecondary,
                    fontSize = 12.sp,
                )
                if (config != null && provider != null) {
                    Text(
                        config.model + " · " + provider.name.ifBlank { provider.baseUrl },
                        color = palette.contentSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (route?.overrides?.isEmpty == false) {
                    Text(
                        buildString {
                            append("覆盖参数：")
                            route.overrides.temperature?.let { append("温度 $it ") }
                            route.overrides.maxTokens?.let { append("最长 $it token") }
                        }.trim(),
                        color = palette.contentSecondary, fontSize = 11.sp,
                    )
                }
                if (problem != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = palette.error, modifier = Modifier.size(13.dp))
                        Text(problem, color = palette.error, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { picking = feature }) { Text("选模型", fontSize = 12.sp) }
                    TextButton(onClick = { tuning = feature }) { Text("覆盖参数", fontSize = 12.sp) }
                }
            }
        }
    }

    picking?.let { feature ->
        val bound = data.routes[feature.name]?.configId.orEmpty()
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("给「${feature.label}」选模型") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item {
                        OptionRow(
                            "跟随全局默认",
                            data.configs.firstOrNull { it.id == data.defaultConfigId }?.label?.let { "现在聊天用的是「$it」" } ?: "还没配全局默认",
                            chosen = bound.isEmpty(),
                        ) { vm.bindFeature(feature, ""); picking = null }
                    }
                    items(data.configs, key = { it.id }) { c ->
                        val p = data.providers.firstOrNull { it.id == c.providerId }
                        val caps = c.capabilities.joinToString("、") { it.label }
                        OptionRow(
                            c.label,
                            c.model + " · " + (p?.name?.ifBlank { p.baseUrl } ?: "服务商不在了") + if (caps.isEmpty()) "" else " · $caps",
                            chosen = bound == c.id,
                        ) { vm.bindFeature(feature, c.id, data.routes[feature.name]?.overrides ?: ParamOverrides()); picking = null }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("关闭") } },
        )
    }

    tuning?.let { feature ->
        OverridesDialog(
            feature = feature,
            route = data.routes[feature.name],
            onDismiss = { tuning = null },
            onSave = { temperature, maxTokens ->
                val configId = data.routes[feature.name]?.configId.orEmpty()
                vm.bindFeature(feature, configId, ParamOverrides(temperature, maxTokens))
                tuning = null
            },
        )
    }
}

@Composable
private fun OverridesDialog(
    feature: Feature,
    route: FeatureRoute?,
    onDismiss: () -> Unit,
    onSave: (Float?, Int?) -> Unit,
) {
    val palette = LocalGlassPalette.current
    var temperature by remember { mutableStateOf(route?.overrides?.temperature?.toString().orEmpty()) }
    var maxTokens by remember { mutableStateOf(route?.overrides?.maxTokens?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${feature.label}」的参数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "只覆盖这一个功能，别的功能不受影响。空着就用模型配置里的默认值。",
                    color = palette.contentSecondary, fontSize = 11.sp, lineHeight = 15.sp,
                )
                OutlinedTextField(
                    temperature, { temperature = it.filter { ch -> ch.isDigit() || ch.code == 46 } },
                    label = { Text("温度（0–2，可空）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    maxTokens, { maxTokens = it.filter { ch -> ch.isDigit() } },
                    label = { Text("最长回复 token（可空）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(temperature.toFloatOrNull(), maxTokens.toIntOrNull()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
