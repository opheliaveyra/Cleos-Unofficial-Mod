package com.cleo.cleos.data

import com.cleo.cleos.data.db.Feature

/** 解析出来的一条**可以用的**模型：哪家、哪个型号、什么参数。 */
data class ResolvedModel(
    val provider: Provider,
    val config: ModelConfig,
    /** 这次实际用的温度：配置的默认，被功能的覆盖压过。 */
    val temperature: Float?,
    val maxTokens: Int?,
    /**
     * 这个功能**没有单独绑**，用的是全局默认。
     *
     * 界面靠它显示"跟随全局"；调用那边靠它判断"要不要提一句"。
     */
    val inherited: Boolean,
) {
    val baseUrl: String get() = provider.baseUrl
    val model: String get() = config.model
    val kind: ApiKind get() = provider.kind
    val label: String get() = config.label
}

/**
 * 解析的结果。
 *
 * **[Broken] 带着一句人话**，而不是一个空值或者"退回上一个能用的"——
 * 规格里那句"配置失效或能力不匹配时明确提示；调用失败不得静默切换服务商"，
 * 落在这里就是：**拿不到就用不了，并且说清楚为什么**。
 */
sealed interface RouteResult {
    data class Ready(val model: ResolvedModel) : RouteResult
    data class Broken(val why: String) : RouteResult
}

/**
 * **所有调用统一从这里拿模型。**
 *
 * 在这之前，六个功能各写各的"配了就用配的、没配就用主模型"，
 * 于是同一个规则有六份实现，六份都可能不一样。现在只有这一个入口：
 *
 * ```
 * 功能绑了配置？ ── 是 ──> 用那条
 *        └─ 否 ──> 全局默认（也就是聊天用的那条）
 *                    └─ 也没有？ ──> Broken：明说没配
 * ```
 *
 * 拿到的 [ResolvedModel] 里连参数都算好了（配置默认被功能覆盖压过），
 * 调用方不需要再知道"覆盖"这回事。
 */
object ModelRoutes {
    fun resolve(data: ModelRegistryData, feature: Feature): RouteResult {
        val route = data.routes[feature.name]
        val bound = route?.configId.orEmpty()
        val configId = bound.ifEmpty { data.defaultConfigId }

        if (configId.isBlank()) {
            return RouteResult.Broken("还没有配置任何模型 —— 先到设置里加一个服务商和模型。")
        }
        val config = data.configs.firstOrNull { it.id == configId && !it.forgotten }
            ?: return RouteResult.Broken(
                if (bound.isNotEmpty()) "「${feature.label}」绑的那条模型配置已经不在了（可能被删过）—— 重新给它选一个。"
                else "全局默认的那条模型配置已经不在了 —— 到设置里重新指一个。",
            )
        val provider = data.providers.firstOrNull { it.id == config.providerId }
            ?: return RouteResult.Broken(
                "「${config.label}」指向的服务商已经不在了（可能被删过）—— 到设置里重新指一个。",
            )
        if (provider.baseUrl.isBlank()) {
            return RouteResult.Broken("服务商「${provider.name.ifBlank { provider.baseUrl }}」还没填接口地址。")
        }
        if (config.model.isBlank()) {
            return RouteResult.Broken("「${config.label}」还没填模型 ID。")
        }
        val overrides = route?.overrides ?: ParamOverrides()
        return RouteResult.Ready(
            ResolvedModel(
                provider = provider,
                config = config,
                temperature = overrides.temperature ?: config.temperature,
                maxTokens = overrides.maxTokens ?: config.maxTokens,
                inherited = bound.isEmpty(),
            ),
        )
    }

    /** 这个功能现在用的是哪条配置（界面显示用；没有就是空串）。 */
    fun configIdFor(data: ModelRegistryData, feature: Feature): String =
        data.routes[feature.name]?.configId.orEmpty().ifEmpty { data.defaultConfigId }

    /**
     * 这个功能要的能力，这条配置有没有。
     *
     * 返回缺的那些；空集表示没问题。**这是"选择时检查"的那一半** ——
     * 图片识别绑一个没声明图片能力的模型，这里就会报出来，
     * 而不是等人发了图发现没用。
     */
    fun missingCapabilities(data: ModelRegistryData, feature: Feature, configId: String): Set<Capability> {
        val need = feature.needs
        if (need.isEmpty()) return emptySet()
        val config = data.configs.firstOrNull { it.id == configId } ?: return need
        return need.filterNot { it in config.capabilities }.toSet()
    }

    /**
     * 能力不匹配时给一句人话；匹配就返回 null。
     *
     * **调用前也查一遍** —— 配置是能被改的，绑的时候对、用的时候不一定还对。
     */
    fun capabilityProblem(data: ModelRegistryData, feature: Feature, configId: String): String? {
        val missing = missingCapabilities(data, feature, configId)
        if (missing.isEmpty()) return null
        val what = missing.joinToString("、") { it.label }
        return "「${feature.label}」需要「$what」能力，但绑的这条模型没有声明它 —— " +
            "到设置里给「${feature.label}」换一个能干的，或者在那条模型配置上点「测一下」验一下。"
    }

    /**
     * 真正要请求的地址，**按服务商的接口类型给**。
     *
     * OpenAI 兼容那套走 `/chat/completions`（[ApiEndpoint] 自己会拼）。
     * 另外两种协议还没实现 —— 这里照样把地址算出来，但 [unsupportedKind] 会拦住它，
     * 免得拿 OpenAI 的请求体发到 Anthropic 的端点上，换回一个看不懂的 400。
     */
    fun chatUrl(provider: Provider): String = when (provider.kind) {
        ApiKind.OpenAi -> provider.baseUrl
        ApiKind.Anthropic -> provider.baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/messages"
        ApiKind.Gemini -> provider.baseUrl
    }

    /** 接口类型还没实现就返回一句人话，实现了返回 null。 */
    fun unsupportedKind(provider: Provider): String? = when (provider.kind) {
        ApiKind.OpenAi -> null
        ApiKind.Anthropic -> "「${provider.name}」用的是 Anthropic 原生协议 —— 这个还没做，先在设置里把它改成「OpenAI 兼容」（大多数中转站都兼容）。"
        ApiKind.Gemini -> "「${provider.name}」用的是 Gemini 原生协议 —— 这个还没做，先改成「OpenAI 兼容」，或者用中转服务。"
    }
}
