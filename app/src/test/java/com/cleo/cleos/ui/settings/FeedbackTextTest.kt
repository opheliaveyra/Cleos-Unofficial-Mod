package com.cleo.cleos.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/** 反馈文本固定带什么、不带什么。 */
class FeedbackTextTest {
    private fun info(errors: String? = "工具诊断：\nMCP 401") = FeedbackInfo(
        version = "0.1.54-mod（226）",
        system = "Android 15（API 35）· Xiaomi 23127PN0CC",
        page = "设置 › 关于",
        time = "2026-10-17 13:00",
        errors = errors,
    )

    @Test
    fun itCarriesTheVersionSystemPageAndDescription() {
        val text = feedbackText(info(), "点开外部服务就闪退", 2)
        assertTrue(text.contains("0.1.54-mod（226）"))
        assertTrue(text.contains("Android 15"))
        assertTrue(text.contains("设置 › 关于"))
        assertTrue(text.contains("点开外部服务就闪退"))
        assertTrue(text.contains("截图：2 张"))
    }

    @Test
    fun itSaysSoWhenThereIsNothingToShow() {
        val text = feedbackText(info(errors = null), "", 0)
        assertTrue(text.contains("（没有写）"))
        assertTrue(text.contains("（没有记录）"))
    }

    @Test
    fun itSaysWhatItDoesNotContain() {
        val text = feedbackText(info(), "x", 0)
        assertTrue(text.contains("未包含密钥、Token、聊天记录或私人附件"))
        assertTrue(text.contains("不会自动发送"))
    }
}
