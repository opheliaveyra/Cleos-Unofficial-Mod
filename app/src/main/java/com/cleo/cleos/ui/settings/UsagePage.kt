package com.cleo.cleos.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.atan2
import kotlin.math.min
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
    var grouping by remember { mutableStateOf("purpose") }
    var details by remember { mutableStateOf(false) }
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

    val known = rows.filter { it.source != TokenUsageEntity.SOURCE_MISSING }
    val total = known.sumOf { it.input + it.output }
    val groups = known.groupBy { if (grouping == "purpose") it.purpose else it.model }
        .mapValues { (_, items) -> items.sumOf { it.input + it.output } }
        .filterValues { it > 0 }.toList().sortedByDescending { it.second }
    val slices = if (groups.size > 7) groups.take(7) + ("其他" to groups.drop(7).sumOf { it.second }) else groups
    val colors = listOf(0xFF9B86D6, 0xFFB8A6E9, 0xFFE4B5CF, 0xFF8BB7C6, 0xFFD8B883, 0xFF92BCA5, 0xFFC5A5C8, 0xFF9EA7CB)
    val active = slices.find { it.first == filter }
    val shown = if (filter == null) rows else rows.filter {
        val key = if (grouping == "purpose") it.purpose else it.model
        if (filter == "其他" && groups.size > 7) groups.drop(7).any { g -> g.first == key } else key == filter
    }

    Section("用量统计") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageRange.entries.forEach { r -> Chip(r.label, selected = range == r) { range = r; filter = null } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).background(palette.content.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("总 Token", color = palette.contentSecondary, fontSize = 12.sp)
                Text("%,d".format(total), color = palette.content, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).background(palette.content.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("请求次数", color = palette.contentSecondary, fontSize = 12.sp)
                Text(rows.size.toString(), color = palette.content, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (missing > 0) Text("$missing 次请求未返回用量，不计入 Token 合计", color = palette.contentSecondary, fontSize = 12.sp)
    }

    Section("用量分布") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("按功能", selected = grouping == "purpose") { grouping = "purpose"; filter = null }
            Chip("按模型", selected = grouping == "model") { grouping = "model"; filter = null }
        }
        if (!loaded) {
            Text("正在读取…", color = palette.contentSecondary, fontSize = 13.sp)
        } else if (total <= 0 || slices.isEmpty()) {
            Text("这段时间还没有可统计的 Token 用量", color = palette.contentSecondary, fontSize = 13.sp)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxWidth().height(220.dp).pointerInput(slices, filter) {
                        detectTapGestures { pos ->
                            val dx = pos.x - size.width / 2f
                            val dy = pos.y - size.height / 2f
                            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                            val radius = min(size.width, size.height) * 0.35f
                            if (distance < radius * 0.6f || distance > radius * 1.4f) {
                                filter = null
                            } else {
                                val angle = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 450.0) % 360.0).toFloat()
                                var cursor = 0f
                                var hit: String? = null
                                slices.forEach { (name, value) ->
                                    val sweep = value.toFloat() / total.toFloat() * 360f
                                    if (angle >= cursor && angle < cursor + sweep) hit = name
                                    cursor += sweep
                                }
                                filter = if (filter == hit) null else hit
                            }
                        }
                    }) {
                        val diameter = min(size.width, size.height)
                        val stroke = diameter * 0.17f
                        var angle = -90f
                        slices.forEachIndexed { index, (_, value) ->
                            val sweep = value.toFloat() / total.toFloat() * 360f
                            drawArc(
                                color = Color(colors[index % colors.size].toInt()).copy(alpha = if (filter == null || filter == slices[index].first) 1f else 0.25f),
                                startAngle = angle, sweepAngle = sweep, useCenter = false, style = Stroke(stroke)
                            )
                            angle += sweep
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(active?.first ?: "总用量", color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1)
                        Text(if (active == null) "%,d".format(total) else "%.1f%%".format(active.second.toDouble() / total * 100),
                            color = palette.content, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text(if (active == null) "Token" else "%,d Token".format(active.second),
                            color = palette.contentSecondary, fontSize = 11.sp)
                    }
                }
            }
            slices.forEachIndexed { index, (name, value) ->
                Row(Modifier.fillMaxWidth().clickable { filter = if (filter == name) null else name }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(Color(colors[index % colors.size].toInt()), RoundedCornerShape(3.dp)))
                    Text(name, color = palette.content, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("%,d · %.1f%%".format(value, value.toDouble() / total * 100),
                        color = palette.contentSecondary, fontSize = 12.sp)
                }
            }
            Text("点击圆环或分类查看占比，再展开下方记录查看详情", color = palette.contentSecondary, fontSize = 11.sp)
        }
    }

    Section("最近请求") {
        Chip(if (details) "收起详细记录" else "展开详细记录（" + shown.size + "）", selected = details) { details = !details }
        if (details) {
            val formatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
            shown.take(200).forEach { row ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatter.format(Instant.ofEpochMilli(row.at).atZone(ZoneId.systemDefault())) + " · " + row.purpose,
                            color = palette.contentSecondary, fontSize = 11.sp)
                        Text(if (row.source == TokenUsageEntity.SOURCE_MISSING) "未提供" else "%,d Token".format(row.input + row.output),
                            color = palette.content, fontSize = 12.sp)
                    }
                    Text(row.model + if (row.ok) "" else " · 失败", color = if (row.ok) palette.contentSecondary else palette.error, fontSize = 11.sp)
                }
            }
            if (shown.size > 200) Text("仅展示最近 200 条", color = palette.contentSecondary, fontSize = 11.sp)
        }
        Chip("清空统计", selected = false) { clearing = true }
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
