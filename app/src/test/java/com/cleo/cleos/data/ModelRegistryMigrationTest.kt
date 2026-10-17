package com.cleo.cleos.data

import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.db.FeatureModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把老结构搬进新的三层。
 *
 * **这里写错就直接动到人已经在用的配置**，所以每一条都当正经事测。
 * 尤其是"同一个型号不许建两份"和"能力别搬丢了"——
 * 前者会让人看见一堆一模一样的配置，后者会让人白按一次「测一下」。
 */
class ModelRegistryMigrationTest {
    private var n = 0
    private fun ids(): () -> String = { "id-${++n}" }

    private fun plan(
        profiles: List<ModelProfile> = emptyList(),
        chatUrl: String? = "https://api.deepseek.com",
        chatModel: String? = "deepseek-flash",
        spokenOn: Boolean = false,
        spokenUrl: String? = null,
        spokenModel: String? = null,
        features: Map<Feature, FeatureModel> = emptyMap(),
    ) = ModelRegistryMigration.plan(
        legacyProfiles = profiles,
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
    fun theChatModelBecomesTheGlobalDefault() {
        val p = plan()
        assertEquals(1, p.configs.size)
        assertEquals("deepseek-flash", p.configs[0].model)
        assertEquals(p.configs[0].id, p.defaultConfigId)
    }

    @Test
    fun theAddressBecomesAProvider() {
        val p = plan()
        assertEquals(1, p.providers.size)
        assertEquals("https://api.deepseek.com", p.providers[0].baseUrl)
        // 预设里认识这个地址，就用它的名字；不认识的话退成主机名。
        assertEquals("DeepSeek", p.providers[0].name)
        // 老结构里没有"接口类型"这回事，一律按唯一实现过的那种。
        assertEquals(ApiKind.OpenAi, p.providers[0].kind)
    }

    @Test
    fun twoModelsOnOneServiceShareOneProvider() {
        val p = plan(
            features = mapOf(Feature.Recap to FeatureModel("https://api.deepseek.com", "deepseek-cheap")),
        )
        assertEquals("同一个地址只该有一个服务商", 1, p.providers.size)
        assertEquals("但型号是两条配置", 2, p.configs.size)
        assertTrue("两条都挂在同一个服务商下", p.configs.all { it.providerId == p.providers[0].id })
    }

    @Test
    fun theSameModelUsedTwiceIsOnlyOneConfig() {
        val p = plan(features = mapOf(Feature.Feed to FeatureModel("https://api.deepseek.com", "deepseek-flash")))
        assertEquals("聊天和朋友圈用同一个型号 —— 只建一条", 1, p.configs.size)
        assertEquals(p.defaultConfigId, p.routes[Feature.Feed.name]?.configId.orEmpty())
    }

    @Test
    fun verifiedCapabilitiesComeAlong() {
        // 人点过「测一下」验出来的能力，搬家时丢了等于白按一次。
        val profiles = listOf(
            ModelProfile(id = "old", name = "小米", baseUrl = "https://b/v1", model = "mimo",
                directImage = true, directVideo = true, userSaved = true),
        )
        val p = plan(profiles = profiles, features = mapOf(Feature.Vision to FeatureModel("https://b/v1", "mimo")))
        val config = p.configs.first { it.model == "mimo" }
        assertEquals(setOf(Capability.Image, Capability.Video), config.capabilities)
    }

    @Test
    fun theOldNameIsKept() {
        val profiles = listOf(ModelProfile(id = "old", name = "我给它起的名字", baseUrl = "https://b/v1", model = "mimo", userSaved = true))
        val p = plan(profiles = profiles)
        assertEquals("我给它起的名字", p.configs.first { it.model == "mimo" }.name)
    }

    @Test
    fun theSpokenModelIsCarriedOverOnlyWhenItIsOn() {
        val off = plan(spokenOn = false, spokenUrl = "https://b/v1", spokenModel = "speak")
        assertEquals(1, off.configs.size)
        val on = plan(spokenOn = true, spokenUrl = "https://b/v1", spokenModel = "speak")
        assertEquals(2, on.configs.size)
    }

    @Test
    fun halfFilledFeatureBindingsAreSkipped() {
        val p = plan(features = mapOf(Feature.Feed to FeatureModel("", "x"), Feature.Recap to FeatureModel("https://b/v1", "")))
        assertTrue("半填的不该建配置，也不该建绑定", p.configs.none { it.model == "x" })
        assertTrue(p.routes.isEmpty())
    }

    @Test
    fun aTrailingSlashAndDifferentCaseAreTheSamePlace() {
        val profiles = listOf(ModelProfile(id = "old", name = "x", baseUrl = "https://API.DeepSeek.com/", model = "deepseek-flash", userSaved = true))
        val p = plan(profiles = profiles)
        assertEquals("同一家不该被拆成两个服务商", 1, p.providers.size)
        // 老配置、主模型、功能绑定三处都指向"深度学习这家 + 这个型号"，
        // 大小写和结尾斜杠不同也算同一件事 —— 归一之后只该剩一条，而且是聊天用的那条。
        assertEquals("同一个型号不该建两份", 1, p.configs.size)
        assertEquals("它就是全局默认", p.configs[0].id, p.defaultConfigId)
        assertEquals("deepseek-flash", p.configs[0].model)
    }

    @Test
    fun nothingAtAllGivesAnEmptyPlan() {
        val p = plan(chatUrl = "", chatModel = "")
        assertTrue(p.isEmpty)
        assertTrue(p.providers.isEmpty())
    }
}
