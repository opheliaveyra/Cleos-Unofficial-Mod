package com.cleo.cleos.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量事件。
 *
 * **这个测试是为了"视频被悄悄丢掉"那次加的。** 有些模型（GPT 全系、智谱 glm）收到视频会
 * 直接扔掉、照样返回 200，模型的回答看起来一本正经，其实它什么都没看到。
 * 唯一能程序化发现的信号就是用量里的 `prompt_tokens`。
 *
 * 而用量帧有个坑：**它没有 `choices`** —— 服务在流的最后单独发一帧。解析器里原本有一句
 * "没有 choices 就返回空"，会把整帧丢掉。所以这里第一件事就是盯住这个。
 */
class StreamParserUsageTest {
    private fun feed(parser: StreamParser, json: String) = parser.feed(json)

    @Test
    fun aUsageFrameWithoutChoicesStillComesThrough() {
        // 真的长这样：只有 usage，没有 choices。
        val parser = StreamParser()
        val events = feed(parser, """{"id":"x","object":"chat.completion.chunk","usage":{"prompt_tokens":5123,"completion_tokens":88}}""")
        val usage = events.filterIsInstance<ChatEvent.Usage>().singleOrNull()
        assertTrue("用量帧被丢掉了 —— 那就再也发现不了'视频没进去'", usage != null)
        assertEquals(5123, usage!!.prompt)
        assertEquals(88, usage.completion)
    }

    @Test
    fun aUsageFrameAlongsideChoicesAlsoComesThrough() {
        // 有些服务把它挂在带 choices 的最后一帧上。
        val parser = StreamParser()
        val events = feed(parser, """{"choices":[{"delta":{"content":"好"}}],"usage":{"prompt_tokens":40}}""")
        assertTrue(events.any { it is ChatEvent.Usage })
        assertTrue(events.any { it is ChatEvent.Delta })
    }

    @Test
    fun aFrameWithNeitherIsStillQuiet() {
        // 大多数帧既没有用量也没有文字（比如只报了个 role）；这种不该冒出个空事件。
        val parser = StreamParser()
        assertTrue(feed(parser, """{"choices":[{"delta":{"role":"assistant"}}]}""").isEmpty())
    }

    @Test
    fun aBrokenUsageFieldIsIgnoredRatherThanGuessed() {
        // 用量给的是个字符串（有的中转站这么干）：当没有，别硬猜成一个数 ——
        // 猜错了会**误报**"视频没进去"，那比不报还烦人。
        val parser = StreamParser()
        val events = feed(parser, """{"usage":{"prompt_tokens":"很多"},"choices":[{"delta":{"content":"嗨"}}]}""")
        assertTrue(events.none { it is ChatEvent.Usage })
        assertTrue(events.any { it is ChatEvent.Delta })
    }

    @Test
    fun theVideoFloorLeavesRoomForEveryRealProvider() {
        // 实测一段十秒视频：Seed/Qwen 约 5000、Gemini 约 700、Kimi 约 14000。
        // 门槛取 200，离正常值远得很，而视频被丢掉时只有几十 —— 不会误伤。
        assertTrue(ChatRepository.VIDEO_TOKENS_MIN >= 100)
        assertTrue(ChatRepository.VIDEO_TOKENS_MIN <= 700)
    }
}
