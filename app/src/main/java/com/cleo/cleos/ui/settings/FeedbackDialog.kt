package com.cleo.cleos.ui.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.cleo.cleos.CrashLog
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 反馈时自动整理的那几样东西。**只有这些**：没有密钥、Token、聊天正文或附件。 */
data class FeedbackInfo(
    val version: String,
    val system: String,
    /** 当前在哪个页面（比如「设置 › 关于」）。 */
    val page: String,
    val time: String,
    /** 最近的脱敏错误（闪退记录、后台出错、工具诊断），可能为空。 */
    val errors: String?,
)

/**
 * 拼出给用户看、复制、导出的那段反馈文本。
 *
 * 拆成纯函数是为了能测：**里面不该出现任何只有用户自己知道的东西** ——
 * 聊天正文、附件名、Key / Token 都不在这里，只抄系统信息和脱敏错误。
 */
fun feedbackText(info: FeedbackInfo, description: String, shots: Int): String = buildString {
    appendLine("Cleos 反馈")
    appendLine("版本：${info.version}")
    appendLine("系统：${info.system}")
    appendLine("页面：${info.page}")
    appendLine("时间：${info.time}")
    if (shots > 0) appendLine("截图：$shots 张（导出时一起放进同一个文件夹）")
    appendLine()
    appendLine("【描述】")
    appendLine(description.trim().ifEmpty { "（没有写）" })
    appendLine()
    appendLine("【最近的错误（已脱敏）】")
    appendLine(info.errors?.trim()?.takeIf { it.isNotEmpty() } ?: "（没有记录）")
    appendLine()
    appendLine("— 以上由 Cleos 自动整理，未包含密钥、Token、聊天记录或私人附件；不会自动发送。")
}

/**
 * 「反馈问题」：把版本、系统、当前页面和最近的脱敏错误整理好，
 * 让用户先看、再决定复制或导出 —— **不自动上传，也不读聊天内容**。
 */
@Composable
internal fun FeedbackDialog(page: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val c = appContainer()
    var description by remember { mutableStateOf("") }
    var shots by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var hint by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        shots = uris.take(3)
    }
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName}（versionCode ${info.longVersionCode}）"
        }.getOrDefault("未知")
    }
    val system = remember { "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）· ${Build.MANUFACTURER} ${Build.MODEL}" }
    val time = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()) }
    // 工具诊断是脱敏的（模型名、工具名、错误类别），闪退/后台记录只有出错位置。
    val errors by produceState<String?>(null) {
        value = buildList {
            runCatching { c.toolDiagnostics.report(pinned = true) }.getOrNull()?.let { add("工具诊断：\n$it") }
            CrashLog.read(context)?.let { add("上次闪退：\n$it") }
            CrashLog.readNote(context)?.let { add("后台出错：\n$it") }
        }.joinToString("\n\n").takeIf { it.isNotBlank() }
    }
    val info = FeedbackInfo(version, system, page, time, errors)
    val text = feedbackText(info, description, shots.size)

    fun copy() {
        clipboard.setText(AnnotatedString(text))
        hint = "复制好了，发之前可以再看一眼。"
    }

    fun export() {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "feedback").apply { mkdirs() }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val file = File(dir, "feedback-$stamp.txt")
                    file.writeText(text)
                    // 用户自己选的截图才一起导出，按顺序命名。
                    shots.forEachIndexed { i, uri ->
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                File(dir, "screenshot-${i + 1}.img").outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }
                    dir
                }
            }
            busy = false
            result.onSuccess { hint = "导出到：${it.absolutePath}" }
                .onFailure { hint = "导出失败：${it.message ?: "再试一次"}" }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        GlassSurface(
            Modifier.fillMaxWidth().padding(12.dp),
            style = palette.card,
            shape = GlassShape.Rounded(26.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(
                Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("反馈问题", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "下面这些会自动整理好，你可以先看、复制或导出。默认不含密钥、Token、完整聊天记录和私人附件；" +
                        "截图只在你明确选择时才会带上。**不会自动发送给任何服务。**",
                    color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                )
                // 预览：就是最后会被复制/导出的那段。
                Text(
                    text,
                    color = palette.contentSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(1200) },
                    label = { Text("补充描述（怎么复现、你看到什么）") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("选择截图（最多 3 张）", selected = false) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    if (shots.isNotEmpty()) Chip("全部去掉", selected = false) { shots = emptyList() }
                }
                if (shots.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shots.forEachIndexed { index, uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = "第 ${index + 1} 张截图",
                            modifier = Modifier.size(56.dp).padding(0.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (busy) "导出中…" else "导出文件", selected = false) { export() }
                    Chip("复制", selected = false) { copy() }
                }
                hint?.let { Text(it, color = palette.accentContent, fontSize = 12.sp, lineHeight = 17.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭", color = palette.content) }
                }
            }
        }
    }
}
