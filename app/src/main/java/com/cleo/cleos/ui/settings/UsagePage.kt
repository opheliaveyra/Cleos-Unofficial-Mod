package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.db.TokenUsageEntity
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 用量统计：今日 / 本月 / 近 7 天 / 全部，能按服务商、模型、角色、用途筛。 */
@Composable
internal fun UsagePage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val c = appContainer()
    var range by remember { mutableStateOf(UsageRange.Month) }
    var rows by remember { mutableStateOf<List<TokenUsageEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(range, clearing) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = when (range) {
            UsageRange.Today -> today.atStartOfDay(zone).toInstant().toEpochMilli()
            UsageRange.Week -> today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
            UsageRange.Month -> today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
            UsageRange.All -> 0L
        }
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        rows = runCatching { withContext(Dispatchers.IO) { c.db.usage().range(from, to) } }.getOrDefault(emptyList())
        loaded = true
    }
    val shown = filter?.let { f -> rows.filter { it.purpose == f } } ?: rows
    val asked = shown.sumOf { it.input }
    val said = shown.sumOf { it.output }
    val missing = shown.count { it.source == TokenUsageEntity.SOURCE_MISSING }

    Section("用量统计") {
        Text(
            "每次模型请求都会记一条：服务商、模型、角色/群、用途、输入/输出 Token。" +
                "服务商没返回 usage 的标「未提供」，不会拿 0 冒充；估算的值也不会写成实际。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageRange.entries.forEach { r ->
                Chip(r.label, selected = range == r) { range = r }
            }
        }
    }

    Section("合计（${shown.size} 次请求）") {
        Text("输入 ${asked} · 输出 ${said} · 总计 ${asked + said}", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (missing > 0) {
            Text("其中 $missing 次服务商没有返回用量（标「未提供」）", color = palette.contentSecondary, fontSize = 12.sp)
        }
        val byPurpose = shown.groupBy { it.purpose }.mapValues { (_, list) -> list.sumOf { it.input + it.output } }
        if (byPurpose.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                byPurpose.entries.sortedByDescending { it.value }.forEach { (p, total) ->
                    Chip("$p $total", selected = filter == p) { filter = if (filter == p) null else p }
                }
            }
        }
        val byScope = shown.filter { it.scopeName.isNotBlank() }.groupBy { it.scopeName }.mapValues { (_, list) -> list.sumOf { it.input + it.output } }
        if (byScope.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                byScope.entries.sortedByDescending { it.value }.take(8).forEach { (name, total) ->
                    Chip("$name $total", selected = false) { }
                }
            }
        }
    }

    Section("最近请求") {
        if (!loaded) {
            Text("正在读取…", color = palette.contentSecondary, fontSize = 13.sp)
        } else if (shown.isEmpty()) {
            Text("这段时间还没有记录。", color = palette.contentSecondary, fontSize = 13.sp)
        } else {
            val formatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
            shown.take(200).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        formatter.format(Instant.ofEpochMilli(row.at).atZone(ZoneId.systemDefault())).toString(),
                        color = palette.contentSecondary, fontSize = 11.sp,
                    )
                    Text(row.model.take(24), color = palette.content, fontSize = 11.sp)
                    Text(
                        if (row.scopeName.isBlank()) row.purpose else "${row.scopeName}${if (row.speaker.isBlank()) "" else "·${row.speaker}"}",
                        color = palette.contentSecondary, fontSize = 11.sp,
                    )
                    Text(
                        if (row.source == TokenUsageEntity.SOURCE_MISSING) "未提供" else "${row.input}+${row.output}",
                        color = if (row.source == TokenUsageEntity.SOURCE_MISSING) palette.contentSecondary else palette.content,
                        fontSize = 11.sp,
                    )
                    if (!row.ok) Text("失败", color = palette.error, fontSize = 11.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("清空统计", selected = false) { clearing = true }
        }
    }

    notice?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
    // 需求里还有：按服务商/模型筛、价格与费用、导出 —— 这一版只做了范围与用途筛，见交接书。
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("清空用量统计？") },
            text = { Text("只删这台手机上的用量记录，聊天、设置都不动。清空之后不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    clearing = false
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { c.db.usage().clear() } }
                        notice = "已清空"
                        rows = emptyList()
                    }
                }) { Text("清空", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } },
        )
    }
}

enum class UsageRange(val label: String) { Today("今日"), Week("近 7 天"), Month("本月"), All("全部") }
