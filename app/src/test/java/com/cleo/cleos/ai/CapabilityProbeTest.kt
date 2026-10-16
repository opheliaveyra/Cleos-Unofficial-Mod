package com.cleo.cleos.ai

import com.cleo.cleos.data.Capability
import com.cleo.cleos.data.ModelProfile
import com.cleo.cleos.data.missingCapabilities
import com.cleo.cleos.data.needs
import com.cleo.cleos.data.with
import com.cleo.cleos.data.db.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 探针的纯逻辑，以及"能力对不对得上"的判断。
 *
 * 这套东西是为了治一个具体的毛病：**在「图像识别」配了一个不会看图的模型**，
 * 而且要到人发了图、发现没用，才知道配错了。
 */
class CapabilityProbeTest {
    @Test
    fun theCodeAvoidsCharactersThatLookAlike() {
        // 0/O、1/I/L 在图片里容易看混 —— 抄错了就分不清是"没看见"还是"看岔了"。
        val code = CapabilityProbe.newCode()
        assertEquals(CapabilityProbe.CODE_LENGTH, code.length)
        assertTrue("不该出现容易混的字符：$code", code.none { it in "01OIL" })
    }

    @Test
    fun everyCodeIsDifferent() {
        // 码是现摇的：**蒙不了**。这是整套探针成立的前提。
        val codes = (1..200).map { CapabilityProbe.newCode(Random(it)) }.toSet()
        assertTrue("码重复太多了", codes.size > 100)
    }

    @Test
    fun aPoliteAnswerStillCounts() {
        // 模型常常会写"这个码是 7K2Q" —— 用"包含"判定，别因为一句客套话把会看图的判成不会。
        assertTrue(CapabilityProbe.matches("这个码是 7K2Q", "7K2Q"))
        assertTrue(CapabilityProbe.matches("7K2Q", "7K2Q"))
        assertTrue(CapabilityProbe.matches("The code is 7K2Q.", "7K2Q"))
    }

    @Test
    fun aWrongOrMissingCodeDoesNotCount() {
        assertFalse(CapabilityProbe.matches("7K2X", "7K2Q"))
        assertFalse(CapabilityProbe.matches("我看不到图片", "7K2Q"))
        assertFalse(CapabilityProbe.matches("", "7K2Q"))
    }

    @Test
    fun onlyAsciiLettersAndDigitsAreKept() {
        // 中文和标点要丢掉，否则"我看不到"这种回答会被当成内容去比对。
        assertEquals("7K2Q", CapabilityProbe.alnumUpper("这个码是：7K2Q！"))
        assertEquals("", CapabilityProbe.alnumUpper("我看不到图片"))
    }

    @Test
    fun aFeatureKnowsWhatItNeeds() {
        assertEquals(setOf(Capability.Image), Feature.Vision.needs)
        assertEquals(setOf(Capability.Video), Feature.Video.needs)
        assertTrue("总结不需要特殊能力", Feature.Recap.needs.isEmpty())
    }

    @Test
    fun aProfileThatHasNotDeclaredTheCapabilityIsReported() {
        // 这正是玖玖遇到的：图像识别指向的配置**没声明能看图** → 要报出来。
        val plain = ModelProfile(id = "1", name = "DeepSeek", baseUrl = "https://a/v1", model = "chat")
        assertEquals(setOf(Capability.Image), missingCapabilities(Feature.Vision, plain))
        assertEquals(setOf(Capability.Video), missingCapabilities(Feature.Video, plain))
        assertTrue("不需要特殊能力的功能永远不报", missingCapabilities(Feature.Recap, plain).isEmpty())
    }

    @Test
    fun aVerifiedCapabilityClearsIt() {
        val able = ModelProfile(id = "1", name = "小米", baseUrl = "https://b/v1", model = "mimo")
            .with(Capability.Image, true)
        assertTrue(able.directImage)
        assertTrue(missingCapabilities(Feature.Vision, able).isEmpty())
        assertEquals("视频还没验过，仍然要报", setOf(Capability.Video), missingCapabilities(Feature.Video, able))
    }

    @Test
    fun nothingIsMissingWhenTheFeatureIsFollowingTheMainModel() {
        // 没单独配（picked = null）时不算"配错"，那种情况由调度那边按主模型的声明处理。
        assertTrue(missingCapabilities(Feature.Vision, null).isEmpty())
    }
}
