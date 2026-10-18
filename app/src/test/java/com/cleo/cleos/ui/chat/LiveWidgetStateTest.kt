package com.cleo.cleos.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0 修复：状态作用域按 HTML 内容稳定区分 —— 同一段 HTML 共享，内容变了隔离。 */
class LiveWidgetStateTest {
    @Test
    fun theSameHtmlGetsTheSameKey() {
        val a = "<div>你好</div>"
        assertEquals(stableKey(a), stableKey(a))
        assertEquals(stableKey(a), stableKey("<div>你好</div>"))
    }

    @Test
    fun differentHtmlGetsDifferentKeys() {
        assertNotEquals(stableKey("<div>a</div>"), stableKey("<div>b</div>"))
        assertNotEquals(stableKey(""), stableKey("<div></div>"))
    }

    @Test
    fun theKeyIsShortHex() {
        val key = stableKey("<button>点我</button>")
        assertEquals(20, key.length) // SHA-1 前 10 字节 = 20 个十六进制字符
        assertTrue(key.all { it in "0123456789abcdef" })
    }
}
