package com.cleo.cleos.data

import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.db.FeatureModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三层结构：服务商 / 模型配置 / 功能绑定，以及"所有调用统一从这里拿模型"那个入口。
 *
 * 这一套是为了治一件具体的事：**六个功能各写各的"配了就用配的、没配就用主模型"**，
 * 于是同一条规则有六份实现，六份都可能不一样。现在规则只有一份，也就只有一份要测。
 */
class ModelRoutesTest {
    private val provider = Provider(id = "p1", name = "小米", baseUrl = "https://api.xiaomimimo.com/v1", kind = ApiKind.OpenAi)
    private val chat = ModelConfig(id = "c-chat", providerId = "p1", model = "mimo-chat")
    private val vision = ModelConfig(id = "c-vision", providerId = "p1", model = "mimo-vl", capabilities = setOf(Capability.Image))

    private fun data(
        configs: List<ModelConfig> = listOf(chat, vision),
        routes: Map<String, FeatureRoute> = emptyMap(),
        defaultConfigId: String = "c-chat",
        providers: List<Provider> = listOf(provider),
    ) = ModelRegistryData(
        providers = providers,
        configs = configs,
        routes = routes,
        defaultConfigId = defaultConfigId,
    )

    // ---- 继承 ----

    @Test
    fun aFunctionWithNothingBoundInheritsTheGlobalDefault() {
        // 规格里那句"未配置的功能继承全局默认模型"，就是这条。
        val result = ModelRoutes.resolve(data(), Feature.Recap)
        val ready = result as? RouteResult.Ready ?: error("应该能用，却是：$result")
        assertEquals("mimo-chat", ready.model.model)
        assertTrue("要说出来它是继承来的，界面才能显示「跟随全局」", ready.model.inherited)
    }

    @Test
    fun aBoundFunctionUsesWhatItIsBoundTo() {
        val result = ModelRoutes.resolve(data(routes = mapOf("Vision" to FeatureRoute("c-vision"))), Feature.Vision)
        val ready = result as? RouteResult.Ready ?: error("应该能用，却是：$result")
        assertEquals("mimo-vl", ready.model.model)
        assertTrue("自己绑的就不是继承", !ready.model.inherited)
    }

    // ---- 坏了要说清楚 ----

    @Test
    fun withNothingConfiguredItSaysSoInsteadOfGuessing() {
        val result = ModelRoutes.resolve(ModelRegistryData(), Feature.Chat)
        assertTrue(result is RouteResult.Broken)
        assertTrue("要告诉人下一步做什么", (result as RouteResult.Broken).why.contains("设置"))
    }

    @Test
    fun aDeletedConfigIsNamedAsTheReason() {
        // 绑了一条后来被删掉的配置：**不能悄悄换成别的**，要说出来是哪条没了。
        val result = ModelRoutes.resolve(data(routes = mapOf("Feed" to FeatureRoute("gone"))), Feature.Feed)
        val broken = result as? RouteResult.Broken ?: error("该报错，却成了：$result")
        assertTrue(broken.why.contains("朋友圈"))
    }

    @Test
    fun aDeletedProviderIsNamedAsTheReason() {
        val result = ModelRoutes.resolve(data(providers = emptyList()), Feature.Chat)
        val broken = result as? RouteResult.Broken ?: error("该报错，却成了：$result")
        assertTrue(broken.why.contains("服务商"))
    }

    @Test
    fun anEmptyModelIdIsCaughtBeforeItBecomesABadRequest() {
        val blank = ModelConfig(id = "c-blank", providerId = "p1", model = "")
        val result = ModelRoutes.resolve(data(configs = listOf(blank), defaultConfigId = "c-blank"), Feature.Chat)
        assertTrue(result is RouteResult.Broken)
    }

    @Test
    fun anEmptyBaseUrlIsCaughtToo() {
        val nowhere = Provider(id = "p1", name = "空地址", baseUrl = "")
        val result = ModelRoutes.resolve(data(providers = listOf(nowhere)), Feature.Chat)
        val broken = result as? RouteResult.Broken ?: error("该报错，却成了：$result")
        assertTrue(broken.why.contains("接口地址"))
    }

    // ---- 参数：配置默认 + 功能覆盖 ----

    @Test
    fun theFunctionsOverrideWinsOverTheConfigsDefault() {
        val warm = chat.copy(temperature = 0.9f, maxTokens = 4096)
        val d = data(
            configs = listOf(warm),
            routes = mapOf("Recap" to FeatureRoute("c-chat", ParamOverrides(temperature = 0.2f))),
        )
        val ready = ModelRoutes.resolve(d, Feature.Recap) as RouteResult.Ready
        assertEquals(0.2f, ready.model.temperature)
        assertEquals("没覆盖的那个还是用配置里的", 4096, ready.model.maxTokens)
    }

    @Test
    fun withNoOverrideTheConfigsOwnValuesComeThrough() {
        val warm = chat.copy(temperature = 0.9f)
        val ready = ModelRoutes.resolve(data(configs = listOf(warm)), Feature.Chat) as RouteResult.Ready
        assertEquals(0.9f, ready.model.temperature)
        assertNull("配置也没写就是没写，不要编一个默认值出来", ready.model.maxTokens)
    }

    // ---- 能力 ----

    @Test
    fun imageRecognitionBoundToAModelWithoutImageCapabilityIsReported() {
        // 这正是玖玖遇到的那个：在「图像识别」配了一个不会看图的模型。
        val d = data(routes = mapOf("Vision" to FeatureRoute("c-chat")))
        assertEquals(setOf(Capability.Image), ModelRoutes.missingCapabilities(d, Feature.Vision, "c-chat"))
        val problem = ModelRoutes.capabilityProblem(d, Feature.Vision, "c-chat")
        assertTrue("要说清楚缺哪个能力", problem != null && problem.contains("看图"))
        assertTrue("还要说去哪儿改", problem!!.contains("设置"))
    }

    @Test
    fun aVerifiedCapabilityPassesTheCheck() {
        val d = data(routes = mapOf("Vision" to FeatureRoute("c-vision")))
        assertTrue(ModelRoutes.missingCapabilities(d, Feature.Vision, "c-vision").isEmpty())
        assertNull(ModelRoutes.capabilityProblem(d, Feature.Vision, "c-vision"))
    }

    @Test
    fun functionsThatNeedNothingAreNeverBlocked() {
        val d = data()
        assertNull(ModelRoutes.capabilityProblem(d, Feature.Recap, "c-chat"))
        assertNull(ModelRoutes.capabilityProblem(d, Feature.Chat, "c-chat"))
    }

    // ---- 接口类型 ----

    @Test
    fun openAiCompatibleRuns() {
        assertNull(ModelRoutes.unsupportedKind(provider))
    }

    @Test
    fun unimplementedProtocolsSaySoInsteadOfSendingTheWrongShape() {
        // 拿 OpenAI 的请求体发到 Anthropic 的端点上，换回来的是一个看不懂的 400 ——
        // 不如直接说"这个还没做"。
        val anthropic = Provider(id = "p2", name = "Claude", baseUrl = "https://api.anthropic.com", kind = ApiKind.Anthropic)
        val why = ModelRoutes.unsupportedKind(anthropic)
        assertTrue(why != null && why.contains("还没做") && why.contains("OpenAI 兼容"))
    }

    // ---- 删配置之后 ----

    @Test
    fun deletingAConfigSendsItsFunctionsBackToTheDefaultAndSaysWhichOnes() {
        val routes = mapOf(
            "Recap" to FeatureRoute("c-chat"),
            "Feed" to FeatureRoute("c-vision", ParamOverrides(temperature = 0.3f)),
            "Letters" to FeatureRoute("c-other"),
        )
        val (next, hit) = retargetRoutes(routes, setOf("c-vision"))
        assertEquals(setOf(Feature.Feed), hit)
        assertEquals("被删的那条要退回默认", "", next["Feed"]?.configId.orEmpty())
        assertEquals("**参数覆盖要留着** —— 重新绑一条时还能接着用", 0.3f, next["Feed"]?.overrides?.temperature ?: 0f)
        assertEquals("没被牵连的不动", "c-chat", next["Recap"]?.configId.orEmpty())
    }

    @Test
    fun deletingAConfigNobodyUsesReportsNobody() {
        val (_, hit) = retargetRoutes(mapOf("Recap" to FeatureRoute("c-chat")), setOf("c-nobody"))
        assertTrue(hit.isEmpty())
    }

    // ---- 编解码 ----

    @Test
    fun theWholeThingSurvivesARoundTrip() {
        val d = data(routes = mapOf("Vision" to FeatureRoute("c-vision", ParamOverrides(maxTokens = 512))))
        val back = ModelRegistry.decode(ModelRegistry.encode(d))
        assertEquals(d, back)
    }

    @Test
    fun aCorruptDocumentReadsAsNothingConfigured() {
        // 存坏了就当没配 —— 那样界面会提示"还没配置任何模型"，人知道该怎么办；
        // 抛异常的话整个设置页就打不开了。
        assertEquals(ModelRegistryData(), ModelRegistry.decode("{不是 JSON"))
    }

    @Test
    fun fieldsFromANewerVersionAreIgnored() {
        val raw = """{"providers":[],"configs":[],"routes":{},"defaultConfigId":"","futureField":42}"""
        assertEquals(ModelRegistryData(), ModelRegistry.decode(raw))
    }
}
