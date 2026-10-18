package com.cleo.cleos.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.chat.LiveWidget
import com.cleo.cleos.ui.chat.LocalLiveWidgets

/**
 * **测试/验证专用**（不是聊天功能）：把六种典型 HTML 放进 [LiveWidget]，
 * 直接看「聊天内预览」和「放大」两种模式在这台真机上的表现。
 *
 * 打开方式：设置 › 关于 › HTML 渲染自检。不改聊天数据，也不发任何请求。
 */
@Composable
fun HtmlSelfTestDialog(onClose: () -> Unit) {
    val palette = LocalGlassPalette.current
    val samples = remember { htmlSamples() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFF6F2FF))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HTML 渲染自检", color = palette.content, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭", color = palette.content) }
            }
            Text(
                "六种情形各一块：短、长、宽表格、按钮、输入框、动态内容。" +
                    "预览最多 80% 屏；点「点开放大」看完整内容。",
                color = palette.contentSecondary, fontSize = 11.sp,
            )
            Spacer(Modifier.padding(3.dp))
            CompositionLocalProvider(LocalLiveWidgets provides true) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(samples) { item ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(item.first, color = palette.contentSecondary, fontSize = 11.sp)
                            LiveWidget(item.second, palette.content)
                        }
                    }
                }
            }
        }
    }
}

private fun htmlSamples(): List<Pair<String, String>> = listOf(
    "短 HTML：标题 + 一段" to buildString {
        append("<div style=\"background:#e9d5ff;padding:14px;border-radius:14px\">")
        append("<h2 style=\"margin:0 0 6px\">短内容</h2>")
        append("<p style=\"margin:0\">只有一行文字。这个应该完整显示、不留多余空白。</p>")
        append("</div>")
    },
    "长 HTML：十段文字" to buildString {
        append("<div style=\"padding:10px\">")
        append("<h2>长内容</h2>")
        for (i in 1..10) append("<p>第 ").append(i).append(" 段：用来验证超过 80% 屏高时预览会截断、放大后能滚动。</p>")
        append("</div>")
    },
    "宽表格：10 列" to buildString {
        append("<table border=\"1\" cellpadding=\"6\" style=\"border-collapse:collapse\">")
        append("<tr>")
        for (i in 1..10) append("<th>列").append(i).append("</th>")
        append("</tr>")
        for (r in 1..5) {
            append("<tr>")
            for (i in 1..10) append("<td>").append(r).append("-").append(i).append("</td>")
            append("</tr>")
        }
        append("</table>")
    },
    "交互按钮：点一下变字" to "<div style=\"padding:10px\"><p>点下面的按钮，文字应该变成「点过了」。</p>" +
        "<button onclick=\"this.textContent='点过了'\">点我</button></div>",
    "输入框：输入后回显" to "<div style=\"padding:10px\"><p>在框里输入，下面回显。</p>" +
        "<input id=\"i\" oninput=\"document.getElementById('o').textContent=this.value\" placeholder=\"输入点什么\">" +
        "<p>回显：<b id=\"o\"></b></p></div>",
    "动态内容：3 秒后长高" to "<div style=\"padding:10px\"><p>等 3 秒，下面会出现更多内容，高度应该跟着长。</p>" +
        "<div id=\"late\"></div>" +
        "<script>setTimeout(function(){document.getElementById('late').innerHTML=" +
        "'<div style=\"height:260px;background:#c7d2fe;border-radius:12px\">动态追加的内容</div>'},3000);</script></div>",
)
