package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 抽帧的两个纯逻辑：时长格式、取点位置。
 *
 * `frames()` 本身要 MediaMetadataRetriever（真机才行），但**取点算错会让抽出来的
 * 全是黑帧**，所以这条值得单独盯住 —— 首帧经常是黑的，末帧常常取不到。
 */
class VideoFramesTest {
    @Test
    fun clockReadsLikeAPlayer() {
        assertEquals("0:00", VideoFrames.clock(0))
        assertEquals("0:05", VideoFrames.clock(5_000))
        assertEquals("0:42", VideoFrames.clock(42_000))
        assertEquals("1:23", VideoFrames.clock(83_000))
        assertEquals("10:00", VideoFrames.clock(600_000))
        assertEquals("1:00:00", VideoFrames.clock(3_600_000))
    }

    @Test
    fun aNegativeOrUnknownLengthReadsAsZero() {
        assertEquals("0:00", VideoFrames.clock(-1))
        assertEquals("0:00", VideoFrames.clock(999)) // 不到一秒
    }

    @Test
    fun theDefaultIsFiveFrames() {
        // 五帧够看清"这是什么、有谁、大概在干什么"，再多就把图片名额占满了。
        assertEquals(5, VideoFrames.DEFAULT_COUNT)
    }
}
