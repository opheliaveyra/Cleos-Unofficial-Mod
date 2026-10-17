package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量导入的解析规则：**只解析，不保存**。
 *
 * 这里最容易出的错是"看起来能用、其实不能用"：stdio 的配置在 Android 上起不了进程，
 * 却很容易被当成一个普通服务存进去，然后在连接测试里报一个谁也看不懂的错。
 */
class McpImportTest {
    @Test
    fun readsTheUsualMcpServersShape() {
        val r = McpImport.parse(
            """
            {"mcpServers": {
              "天气": {"url": "https://a.example.com/mcp"},
              "老服务": {"url": "https://b.example.com/sse"},
              "本地": {"command": "npx", "args": ["-y", "some-mcp"], "env": {"KEY": "x"}}
            }}
            """.trimIndent(),
        )
        assertEquals(3, r.candidates.size)
        assertEquals("https://a.example.com/mcp", r.candidates.first { it.key == "天气" }.url)
        assertEquals(McpServer.TRANSPORT_HTTP, r.candidates.first { it.key == "天气" }.transport)
        // 地址里带 /sse：按老式 SSE 认（很多服务的默认写法）。
        assertEquals(McpServer.TRANSPORT_SSE, r.candidates.first { it.key == "老服务" }.transport)
    }

    @Test
    fun aLocalCommandIsMarkedUnsupportedInsteadOfSaved() {
        val r = McpImport.parse("""{"mcpServers":{"本地":{"command":"npx","args":["-y","x"]}}}""")
        val local = r.candidates.single()
        assertEquals("stdio", local.transport)
        assertFalse(local.usable)
        assertTrue(local.problem!!.contains("当前环境不支持"))
    }

    @Test
    fun theTypeFieldWinsOverTheUrlGuess() {
        val r = McpImport.parse("""{"mcpServers":{"s":{"url":"https://x.example/mcp","type":"sse"}}}""")
        assertEquals(McpServer.TRANSPORT_SSE, r.candidates.single().transport)
    }

    @Test
    fun headersAreReadAsAnObject() {
        val r = McpImport.parse("""{"mcpServers":{"s":{"url":"https://x/mcp","headers":{"X-Api-Key":"abc","X-Team":"1"}}}}""")
        val h = r.candidates.single().headers
        assertEquals(listOf(McpHeader("X-Api-Key", "abc"), McpHeader("X-Team", "1")), h)
    }

    @Test
    fun aServiceWithoutAnAddressIsReported() {
        val r = McpImport.parse("""{"mcpServers":{"s":{"name":"没有地址"}}}""")
        assertFalse(r.candidates.single().usable)
        assertTrue(r.candidates.single().problem!!.contains("没写地址"))
    }

    @Test
    fun brokenJsonSaysSoInsteadOfThrowing() {
        val r = McpImport.parse("{ not json")
        assertEquals(emptyList<McpImportCandidate>(), r.candidates)
        assertTrue(r.error!!.contains("JSON"))
    }

    @Test
    fun aTokenCanBeBothInItsOwnFieldAndInTheHeaders() {
        val r = McpImport.parse(
            """{"mcpServers":{"s":{"url":"https://x/mcp","token":"Bearer t","headers":{"Authorization":"Bearer h"}}}}""",
        )
        assertEquals("Bearer t", r.candidates.single().token)
        assertTrue(r.candidates.single().headers.any { it.name.equals("Authorization", ignoreCase = true) })
    }

    // ---- 数据模型那两条规则 ----

    @Test
    fun aCustomAuthorizationSuppressesTheBearerToken() {
        val withOwn = McpServer(
            id = "1", url = "https://x/mcp", token = "t",
            headers = listOf(McpHeader("Authorization", "Bearer h")),
        )
        assertTrue(withOwn.hasOwnAuthorization)
        val plain = McpServer(id = "2", url = "https://x/mcp", token = "t")
        assertFalse(plain.hasOwnAuthorization)
    }

    @Test
    fun theOldSingleHeaderStillCounts() {
        // 旧版本存的是 "Name: value" 一行 —— 读进来要并进 headers，不能丢。
        val old = McpServer(id = "1", url = "https://x/mcp", header = "X-Team: 7")
        assertEquals(listOf(McpHeader("X-Team", "7")), old.effectiveHeaders)
    }

    @Test
    fun savingTwiceUnderTheSameNameGetsANewOne() {
        val existing = listOf(McpServer(id = "a", name = "天气"), McpServer(id = "b", name = "天气（2）"))
        assertEquals("天气（3）", com.cleo.cleos.ui.settings.uniqueName("天气", existing))
    }
}
