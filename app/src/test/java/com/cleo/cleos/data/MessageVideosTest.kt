package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息上那串视频名字的编解码。
 *
 * `VideoStore` 本身要 Context（真机才跑得动），但**编解码坏了等于视频丢了**：
 * 存进去的名单读不出来，消息就变成一条空消息，人发的东西凭空没了。
 */
class MessageVideosTest {
    @Test
    fun aVideoRoundTrips() {
        val one = MessageVideo(file = "abc.mp4", durationMs = 12_000, bytes = 3_000_000)
        val back = MessageVideos.decode(MessageVideos.encode(listOf(one)))
        assertEquals(1, back.size)
        assertEquals("abc.mp4", back[0].file)
        assertEquals(12_000, back[0].durationMs)
        assertEquals(3_000_000, back[0].bytes)
    }

    @Test
    fun severalKeepTheirOrder() {
        val list = listOf(MessageVideo("a.mp4"), MessageVideo("b.mov"), MessageVideo("c.avi"))
        assertEquals(listOf("a.mp4", "b.mov", "c.avi"), MessageVideos.decode(MessageVideos.encode(list)).map { it.file })
    }

    @Test
    fun anEmptyListEncodesToNothing() {
        // 没带视频的消息不该在库里留个 "[]" —— 那和空字符串是两回事，多了没意义。
        assertNull(MessageVideos.encode(emptyList()))
        assertTrue(MessageVideos.decode(null).isEmpty())
        assertTrue(MessageVideos.decode("").isEmpty())
    }

    @Test
    fun aCorruptStringReadsAsNoVideos() {
        // 存坏了就当没有。**不能让一段坏 JSON 把整条消息弄没** ——
        // 消息本身（文字、图片）还在，只是视频名单读不出来。
        assertTrue(MessageVideos.decode("{不是 JSON").isEmpty())
        assertTrue(MessageVideos.decode("[{\"file\":123}]").isEmpty())
    }

    @Test
    fun extraFieldsFromANewerVersionAreIgnored() {
        // 以后的版本给 MessageVideo 加了字段，老版本读到不该炸。
        val raw = """[{"file":"x.mp4","durationMs":1000,"bytes":10,"future":"whatever"}]"""
        val back = MessageVideos.decode(raw)
        assertEquals(1, back.size)
        assertEquals("x.mp4", back[0].file)
    }
}
