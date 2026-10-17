package com.cleo.cleos.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.McpImport
import com.cleo.cleos.data.McpImportCandidate
import com.cleo.cleos.data.McpServer
import com.cleo.cleos.data.McpServers
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 批量导入外部服务：**粘贴 JSON 或选一个文件** → 解析 → 预览（勾选）→ 处理重名 → 添加。
 *
 * 三条规矩：
 * - **重名不静默覆盖**：跳过 / 另存 / 覆盖，三选一，而且预览里写清楚会动到哪几条
 * - **部分坏的不拦着好的**：每条单独标问题，能用的照样能加
 * - **stdio 的不收**：Android 上起不了子进程，标成"当前环境不支持"，勾都勾不上
 */
@Composable
internal fun McpImportDialog(
    servers: McpServers,
    /** 从「导入文件」进来的：一打开就弹文件选择。 */
    autoPickFile: Boolean = false,
    onDismiss: () -> Unit,
    onImported: (Int) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<com.cleo.cleos.data.McpImportResult?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 重名怎么处理：跳过 / 另存 / 覆盖。 */
    var conflict by remember { mutableStateOf(Conflict.Skip) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val existing by servers.all.collectAsStateWithLifecycle(emptyList())
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.onSuccess { if (it != null) { text = it; parsed = null; message = null } }
            .onFailure { message = "这个文件读不出来" }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (autoPickFile) filePicker.launch(arrayOf("application/json", "text/plain", "*/*"))
    }
    val results = parsed
    val usable = results?.candidates?.filter { it.usable }.orEmpty()
    val blocked = results?.candidates?.filterNot { it.usable }.orEmpty()
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GlassSurface(
            Modifier.fillMaxWidth(0.96f).padding(horizontal = 8.dp),
            style = palette.card,
            shape = GlassShape.Rounded(26.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("批量导入外部服务", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "支持常见的 mcpServers 结构（粘贴或选文件）。导入前会先列出来，你自己勾。",
                    color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; parsed = null; message = null },
                    label = { Text("把 JSON 粘在这里") },
                    placeholder = { Text("{\"mcpServers\": {…}}") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = text.isNotBlank() && !busy, onClick = {
                        parsed = McpImport.parse(text)
                        val p = parsed
                        message = p?.error
                        picked = p?.candidates?.filter { it.usable }?.map { it.key }?.toSet().orEmpty()
                    }) { Text("解析", color = palette.accentContent) }
                    TextButton(enabled = !busy, onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text("选一个 JSON 文件", color = palette.accentContent)
                    }
                }
                message?.let { Text(it, color = palette.error, fontSize = 12.sp, lineHeight = 17.sp) }
                if (results != null && results.error == null) {
                    Text(
                        "解析出 ${results.candidates.size} 个：能用 ${usable.size} 个，不支持/有问题 ${blocked.size} 个。",
                        color = palette.contentSecondary, fontSize = 12.sp,
                    )
                    // 重名处理：三选一，不静默覆盖。
                    if (conflictRows(results.candidates, existing).isNotEmpty()) {
                        Text("和已有的重名/同地址的（${conflictRows(results.candidates, existing).size} 个）：", color = palette.contentSecondary, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Conflict.entries.forEach { c ->
                                TextButton(onClick = { conflict = c }) {
                                    Text(
                                        (if (conflict == c) "● " else "") + when (c) {
                                            Conflict.Skip -> "跳过"
                                            Conflict.Keep -> "另存一份"
                                            Conflict.Replace -> "覆盖已有的"
                                        },
                                        color = if (conflict == c) palette.accentContent else palette.contentSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                    results.candidates.forEach { c ->
                        val on = c.key in picked
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, enabled = c.usable && !busy, onCheckedChange = {
                                picked = if (on) picked - c.key else picked + c.key
                            })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.name + if (c.usable) "" else " · 当前环境不支持" ,
                                    color = if (c.usable) palette.content else palette.error,
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    c.transportLabel + " · " + (if (c.url.isBlank()) "（没有地址）" else c.url),
                                    color = palette.contentSecondary, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                c.problem?.let { Text(it, color = palette.error, fontSize = 11.sp, lineHeight = 15.sp) }
                            }
                        }
                    }
                    TextButton(
                        enabled = !busy && picked.isNotEmpty(),
                        onClick = {
                            busy = true
                            val chosen = results.candidates.filter { it.usable && it.key in picked }
                            scope.launch {
                                val added = mutableListOf<McpServer>()
                                var skipped = 0
                                for (c in chosen) {
                                    val same = existing.firstOrNull { it.name == c.name || it.url == c.url }
                                    val server = McpServer(
                                        id = UUID.randomUUID().toString(),
                                        name = c.name,
                                        url = c.url,
                                        token = c.token,
                                        headers = c.headers,
                                        transport = c.transport,
                                    )
                                    when {
                                        same == null -> added += server
                                        conflict == Conflict.Skip -> skipped++
                                        conflict == Conflict.Keep -> added += server.copy(name = uniqueName(c.name, existing + added))
                                        else -> added += server.copy(id = same.id)  // 覆盖：沿用它的 id
                                    }
                                }
                                runCatching { servers.saveAll(added) }
                                    .onSuccess {
                                        message = "加了 ${added.size} 个" + if (skipped > 0) "，跳过 $skipped 个重名的" else ""
                                        busy = false
                                        onImported(added.size)
                                    }
                                    .onFailure {
                                        message = "没存上：${it.message ?: "再试一次"}"
                                        busy = false
                                    }
                            }
                        },
                    ) { Text(if (busy) "正在添加…" else "添加所选（${picked.size}）", color = palette.accentContent) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭", color = palette.content) }
                }
            }
        }
    }
}

/** 重名怎么处理。 */
internal enum class Conflict { Skip, Keep, Replace }

/** 和已存在的服务重名或同地址的候选。 */
internal fun conflictRows(candidates: List<McpImportCandidate>, existing: List<McpServer> = emptyList()): List<String> =
    candidates.filter { c -> existing.any { it.name == c.name || it.url == c.url } }.map { it.key }

/** 另存时给一个不撞的名字：名字（2）、（3）…… */
internal fun uniqueName(name: String, existing: List<McpServer>): String {
    var i = 2
    var candidate = "$name（$i）"
    while (existing.any { it.name == candidate }) {
        i++
        candidate = "$name（$i）"
    }
    return candidate
}
