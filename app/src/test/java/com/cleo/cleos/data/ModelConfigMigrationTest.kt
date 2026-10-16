package com.cleo.cleos.data

import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.db.FeatureModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搬家：把"存在 TA 身上"的模型设置，算成"该建哪些配置、谁指向谁"。
 *
 * **这里写错就直接动到人已经在用的数据**，所以每一条都当成正经事来测 ——
 * 尤其是"同一个模型被好几处用到时只建一条"，错了会让人看见一堆一模一样的配置。
 */
class ModelConfigMigrationTest {
    private var counter = 0
    private fun ids(): () -> String = { "id-${++counter}" }

    private fun plan(
        chatUrl: String? = "https://api.deepseek.com",
        chatModel: String? = "deepseek-flash",
        spokenOn: Boolean = false,
        spokenUrl: String? = null,
        spokenModel: String? = null,
        features: Map<Feature, FeatureModel> = emptyMap(),
        existing: List<ModelProfile> = emptyList(),
    ) = ModelConfigMigration.plan(
        existing = existing,
        chatBaseUrl = chatUrl,
        chatModel = chatModel,
        spokenOn = spokenOn,
        spokenBaseUrl = spokenUrl,
        spokenModel = spokenModel,
        legacyFeatures = features,
        nameOf = { url -> if (url == "https://api.deepseek.com") "DeepSeek" else null },
        newId = ids(),
    )

    @Test
    fun theMainModelBecomesAProfileThatIsPointedAt() {
        val p = plan()
        assertEquals(1, p.create.size)
        assertEquals("deepseek-flash", p.create[0].model)
        assertEquals("DeepSeek", p.create[0].name)
        assertEquals(p.create[0].id, p.chatProfileId)
        assertTrue("搬过来的配置要算人保存过的，不然列表里看不见", p.create[0].userSaved == true)
    }

    @Test
    fun theSpokenModelBecomesItsOwnProfileOnlyWhenItIsOn() {
        val off = plan(spokenOn = false, spokenUrl = "https://b/v1", spokenModel = "speak")
        assertEquals(1, off.create.size)
        assertEquals("", off.spokenProfileId)

        counter = 0
        val on = plan(spokenOn = true, spokenUrl = "https://b/v1", spokenModel = "speak")
        assertEquals(2, on.create.size)
        assertTrue(on.spokenProfileId.isNotBlank())
    }

    @Test
    fun featuresPointAtProfilesToo() {
        val p = plan(features = mapOf(Feature.Recap to FeatureModel("https://b/v1", "cheap")))
        assertEquals(2, p.create.size)
        assertTrue(p.featureProfileIds[Feature.Recap]?.isNotBlank() == true)
        assertEquals("cheap", p.create.first { it.id == p.featureProfileIds[Feature.Recap] }.model)
    }

    @Test
    fun theSameModelUsedTwiceIsOnlyCreatedOnce() {
        // 主模型和某个功能其实用同一个模型：只建一条，两边都指它。
        // 错了的话人会看见两条一模一样的配置，而且分不清哪个是哪个。
        val p = plan(features = mapOf(Feature.Feed to FeatureModel("https://api.deepseek.com", "deepseek-flash")))
        assertEquals(1, p.create.size)
        assertEquals(p.chatProfileId, p.featureProfileIds[Feature.Feed])
    }

    @Test
    fun anAlreadySavedProfileIsReusedRatherThanDuplicated() {
        counter = 0
        val existing = listOf(ModelProfile(id = "old", name = "我之前存的", baseUrl = "https://api.deepseek.com", model = "deepseek-flash"))
        val p = plan(existing = existing)
        assertTrue("已经有一条了，不该再建", p.create.isEmpty())
        assertEquals("old", p.chatProfileId)
    }

    @Test
    fun anAlreadySavedProfileIsMatchedDespiteTrailingSlashAndCase() {
        val existing = listOf(ModelProfile(id = "old", name = "x", baseUrl = "https://API.DeepSeek.com/", model = "deepseek-flash"))
        val p = plan(existing = existing)
        assertTrue(p.create.isEmpty())
        assertEquals("old", p.chatProfileId)
    }

    @Test
    fun halfFilledThingsAreSkippedEntirely() {
        counter = 0
        val p = plan(chatUrl = "  ", chatModel = "m", features = mapOf(Feature.Feed to FeatureModel("", "x")))
        assertTrue("地址空着的不该建配置", p.create.isEmpty())
        assertEquals("", p.chatProfileId)
        assertTrue(p.isEmpty)
    }

    @Test
    fun nothingToDoReportsItselfAsEmpty() {
        counter = 0
        val p = plan(chatUrl = "", chatModel = "")
        assertTrue(p.isEmpty)
    }

    @Test
    fun runningItTwiceOnItsOwnResultChangesNothing() {
        // 搬家会跑好几次（每次启动都过一遍），第二次必须什么都不做。
        // 不然配置会越攒越多，人看到的是"怎么冒出来这么多一样的"。
        counter = 0
        val first = plan()
        counter = 0
        val second = plan(existing = first.create)
        assertTrue("第二次不该再建", second.create.isEmpty())
        assertEquals(first.chatProfileId, second.chatProfileId)
    }
}
