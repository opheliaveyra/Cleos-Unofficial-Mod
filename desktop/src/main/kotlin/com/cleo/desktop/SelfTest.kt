package com.cleo.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 无界面自检：起一个本地 mock SSE 服务器，走一遍
 * 「配置 → 角色 → 流式聊天 → 停止 → 保存 → 重新加载」。
 *
 * 不做假验证：每一步都看真实结果，失败会打印 FAIL。
 */
object SelfTest {
    fun run(): Int {
        var failed = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) "  (" + detail + ")" else ""))
            if (!ok) failed++
        }
        // 自检不往用户目录写：用构建目录下的临时数据。
        DesktopStore.file = java.io.File("build/selftest-data/data.json")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex: HttpExchange ->
            val req = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val slow = req.contains("STOP")
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            val pieces = if (slow) listOf("第一段", "第二段", "第三段") else listOf("你好", "，我是", "桌面端")
            for (p in pieces) {
                val json = "{\"choices\":[{\"delta\":{\"content\":\"" + p + "\"}}]}"
                out.write(("data: " + json + "\n\n").toByteArray(StandardCharsets.UTF_8))
                out.flush()
                Thread.sleep(if (slow) 400 else 30)
            }
            out.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
            ex.close()
        }
        server.start()
        val port = server.address.port
        try {
            val provider = ProviderSettings(
                baseUrl = "http://127.0.0.1:" + port + "/v1",
                apiKey = "test-key",
                model = "mock-model",
            )
            val role = Role(id = UUID.randomUUID().toString(), name = "测试角色", persona = "你是测试角色")
            val client = DesktopChatClient()

            var streamed = ""
            val answer = runBlocking {
                client.stream(provider, listOf(ChatMessage("user", "你好"))) { chunk -> streamed += chunk }
            }
            check("流式收到片段", streamed.isNotEmpty(), streamed)
            check("完整回答拼接正确", answer.contains("桌面端"), answer)

            val data = AppData(
                provider = provider,
                roles = listOf(role),
                conversations = mapOf(role.id to listOf(ChatMessage("user", "你好"), ChatMessage("assistant", answer))),
                activeRoleId = role.id,
            )
            DesktopStore.save(data)
            val reloaded = DesktopStore.load()
            check("重启后恢复角色", reloaded.roles.any { it.id == role.id })
            check("重启后恢复消息", reloaded.conversations[role.id]?.size == 2)
            check("重启后恢复服务商配置", reloaded.provider.model == "mock-model")

            var stopped = false
            runBlocking {
                val job = launch {
                    runCatching { client.stream(provider, listOf(ChatMessage("user", "STOP"))) { } }
                }
                delay(450)
                client.cancel()
                job.join()
                stopped = true
            }
            check("停止后请求结束、不挂死", stopped)
        } finally {
            server.stop(0)
        }
        println(if (failed == 0) "SELFTEST: ALL PASS" else "SELFTEST: " + failed + " FAILED")
        return if (failed == 0) 0 else 1
    }
}
