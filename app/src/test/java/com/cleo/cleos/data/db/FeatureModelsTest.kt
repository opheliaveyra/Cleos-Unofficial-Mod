package com.cleo.cleos.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每个功能各自配的模型。
 *
 * 这里最要紧的一条是**"只填一半等于没配"** —— 地址填了、模型名忘了填，
 * 那种配置发出去只会报错；退回去用主模型，至少功能还是好的。
 */
class FeatureModelsTest {
    private fun ta(baseUrl: String = "https://api.deepseek.com", model: String = "deepseek-flash", features: String = "") =
        CompanionEntity(apiBaseUrl = baseUrl, apiModel = model, createdAt = 0, featureModels = features)

    @Test
    fun nothingConfiguredMeansEveryFeatureUsesTheMainModel() {
        val one = ta()
        for (feature in Feature.entries) {
            assertEquals("https://api.deepseek.com", one.modelFor(feature).baseUrl)
            assertEquals("deepseek-flash", one.modelFor(feature).model)
        }
    }

    @Test
    fun aFeatureWithItsOwnModelUsesThatOne() {
        val raw = FeatureModels.encode(mapOf(Feature.Recap to FeatureModel("https://cheap.example/v1", "tiny-model")))
        val one = ta(features = raw)
        assertEquals("tiny-model", one.modelFor(Feature.Recap).model)
        assertEquals("https://cheap.example/v1", one.modelFor(Feature.Recap).baseUrl)
        // 别的功能没配，还是跟着主模型
        assertEquals("deepseek-flash", one.modelFor(Feature.Feed).model)
    }

    @Test
    fun aHalfFilledFeatureFallsBackToTheMainModel() {
        // 只填地址、没填模型名：发出去只会报错，所以当没配。
        val raw = FeatureModels.encode(mapOf(Feature.Recap to FeatureModel("https://cheap.example/v1", "")))
        assertEquals("", raw)
        assertEquals("deepseek-flash", ta(features = raw).modelFor(Feature.Recap).model)

        val onlyModel = FeatureModels.encode(mapOf(Feature.Feed to FeatureModel("", "tiny-model")))
        assertEquals("", onlyModel)
        assertEquals("deepseek-flash", ta(features = onlyModel).modelFor(Feature.Feed).model)
    }

    @Test
    fun brokenJsonMeansNothingIsConfiguredRatherThanACrash() {
        assertTrue(FeatureModels.decode("这不是 JSON").isEmpty())
        assertTrue(FeatureModels.decode("{").isEmpty())
        assertTrue(FeatureModels.decode("").isEmpty())
        assertTrue(FeatureModels.decode("""{"Recap":{"baseUrl":"x","model":"y"}""").isEmpty())
    }

    @Test
    fun aFeatureNameFromTheFutureIsIgnored() {
        // 以后删掉某个功能时，旧存档里的那条不该让整个配置读不出来。
        val kept = FeatureModels.decode("""{"Recap":{"baseUrl":"https://a/v1","model":"m"},"SomethingGone":{"baseUrl":"https://b/v1","model":"n"}}""")
        assertEquals(1, kept.size)
        assertEquals("m", kept[Feature.Recap]?.model)
    }

    @Test
    fun encodeDropsTheHalfFilledOnes() {
        val raw = FeatureModels.encode(
            mapOf(
                Feature.Recap to FeatureModel("https://a/v1", "m"),
                Feature.Feed to FeatureModel("https://b/v1", ""),
            ),
        )
        val back = FeatureModels.decode(raw)
        assertEquals(1, back.size)
        assertEquals("m", back[Feature.Recap]?.model)
        assertNull(back[Feature.Feed])
    }

    @Test
    fun emptyMeansAnEmptyStringNotABrokenOne() {
        assertEquals("", FeatureModels.encode(emptyMap()))
    }
}
