package com.cleo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
import java.util.UUID

fun main(args: Array<String>) {
    if (args.contains("--selftest")) {
        kotlin.system.exitProcess(SelfTest.run())
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Cleos Desktop（测试版）") {
            App()
        }
    }
}

private val Bg = Color(0xFF14151A)
private val Side = Color(0xFF1B1C22)
private val Card = Color(0xFF24262F)
private val Mine = Color(0xFF33406B)
private val Ink = Color(0xFFE6E8F0)
private val Dim = Color(0xFF8A90A0)

@Composable
fun App() {
    var data by remember { mutableStateOf(DesktopStore.load()) }
    fun update(next: AppData) {
        data = next
        DesktopStore.save(next)
    }
    val client = remember { DesktopChatClient() }
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNewRole by remember { mutableStateOf(false) }
    val role = data.roles.firstOrNull { it.id == data.activeRoleId }

    fun send() {
        val r = role ?: return
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        if (data.provider.apiKey.isBlank()) {
            error = "先在「设置」里填服务商地址、API Key 和模型名"
            showSettings = true
            return
        }
        val history = (data.conversations[r.id] ?: emptyList()) + ChatMessage("user", text)
        update(data.copy(conversations = data.conversations + (r.id to history)))
        draft = ""
        error = null
        streaming = ""
        sending = true
        scope.launch {
            try {
                val answer = client.stream(data.provider, history) { chunk -> streaming += chunk }
                val done = (data.conversations[r.id] ?: emptyList()) + ChatMessage("assistant", answer)
                update(data.copy(conversations = data.conversations + (r.id to done)))
            } catch (e: Exception) {
                val partial = streaming
                if (partial.isNotEmpty()) {
                    val done = (data.conversations[r.id] ?: emptyList()) + ChatMessage("assistant", partial)
                    update(data.copy(conversations = data.conversations + (r.id to done)))
                }
                error = e.message ?: "请求失败"
            } finally {
                sending = false
                streaming = ""
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7C9CFF))) {
        Row(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.width(240.dp).fillMaxHeight().background(Side).padding(12.dp)) {
                Text("角色", color = Dim, fontSize = 12.sp)
                Spacer(Modifier.padding(4.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(data.roles) { r ->
                        val active = r.id == data.activeRoleId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) Color(0xFF2A2D3A) else Color.Transparent)
                                .clickable { update(data.copy(activeRoleId = r.id)) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.emoji, fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(r.name, color = Ink, maxLines = 1)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showNewRole = true }) { Text("+ 角色") }
                    TextButton(onClick = { showSettings = true }) { Text("设置") }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (role == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("先在左边新建一个角色", color = Dim)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().background(Side).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(role.emoji + " " + role.name, color = Ink, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        Text(data.provider.model, color = Dim, fontSize = 12.sp)
                    }
                    val messages = data.conversations[role.id] ?: emptyList()
                    val listState = rememberLazyListState()
                    LaunchedEffect(messages.size, streaming.length) {
                        val total = listState.layoutInfo.totalItemsCount
                        if (total > 0) listState.animateScrollToItem(total - 1)
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(messages) { m -> MessageRow(m) }
                        if (streaming.isNotEmpty()) item { MessageRow(ChatMessage("assistant", streaming)) }
                    }
                    error?.let { Text(it, color = Color(0xFFFF6B6B), modifier = Modifier.padding(horizontal = 16.dp)) }
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("说点什么（Enter 发送）") },
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions.Default,
                        )
                        if (sending) {
                            Button(onClick = { client.cancel() }) { Text("停止") }
                        } else {
                            Button(onClick = ::send) { Text("发送") }
                        }
                    }
                }
            }
        }

        if (showSettings) {
            var baseUrl by remember { mutableStateOf(data.provider.baseUrl) }
            var apiKey by remember { mutableStateOf(data.provider.apiKey) }
            var model by remember { mutableStateOf(data.provider.model) }
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("服务商和模型") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("OpenAI 兼容接口即可（DeepSeek、OpenAI、各种中转站都行）。", color = Dim, fontSize = 12.sp)
                        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(model, { model = it }, label = { Text("模型名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        update(data.copy(provider = ProviderSettings(baseUrl.trim(), apiKey.trim(), model.trim())))
                        showSettings = false
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } },
            )
        }

        if (showNewRole) {
            var name by remember { mutableStateOf("") }
            var persona by remember { mutableStateOf("") }
            var emoji by remember { mutableStateOf("🐾") }
            AlertDialog(
                onDismissRequest = { showNewRole = false },
                title = { Text("新建角色") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(name, { name = it }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(emoji, { emoji = it.take(4) }, label = { Text("表情") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(persona, { persona = it }, label = { Text("人设（可选）") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val id = UUID.randomUUID().toString()
                        val role = Role(id = id, name = name.trim().ifEmpty { "新角色" }, emoji = emoji.ifEmpty { "🐾" }, persona = persona.trim())
                        update(data.copy(roles = data.roles + role, activeRoleId = id))
                        showNewRole = false
                    }) { Text("创建") }
                },
                dismissButton = { TextButton(onClick = { showNewRole = false }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun MessageRow(m: ChatMessage) {
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (mine) Mine else Card)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(m.text, color = Ink, fontSize = 14.sp)
        }
    }
}
