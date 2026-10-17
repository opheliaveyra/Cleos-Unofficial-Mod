package com.cleo.cleos.data

import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.db.FeatureModel
import java.util.UUID

/**
 * 把老结构搬成新的三层。
 *
 * **老的样子**：地址和型号混在一起，散在四处 ——
 * TA 身上一对（主模型）、一对（语音）、一份功能 JSON、外加一份"保存过的配置"。
 * **新的样子**：服务商（地址+类型）／模型配置（哪家的哪个型号）／功能绑定，各归各位。
 *
 * 纯函数：**迁移写错就没法回头**，所以"该建哪些、谁指向谁"单独拿出来测，
 * 不跟读写搅在一起。
 */
object ModelRegistryMigration {
    data class Plan(
        val providers: List<Provider>,
        val configs: List<ModelConfig>,
        val routes: Map<String, FeatureRoute>,
        val defaultConfigId: String,
    ) {
        val isEmpty: Boolean get() = configs.isEmpty()
    }

    /**
     * @param legacyProfiles 老那份"保存过的配置"（每条形如 地址+型号），它记着能力探测的结果
     * @param legacyFeatures 老那份"每个功能配了哪个模型"
     * @param nameOf 地址 → 好听的服务商名字（比如 api.deepseek.com → DeepSeek）
     * @param newId 生成 id；传进来是为了纯函数能测（不然每次跑都不一样，没法断言）
     */
    fun plan(
        legacyProfiles: List<ModelProfile>,
        chatBaseUrl: String?,
        chatModel: String?,
        spokenOn: Boolean,
        spokenBaseUrl: String?,
        spokenModel: String?,
        legacyFeatures: Map<Feature, FeatureModel>,
        nameOf: (String) -> String? = { null },
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Plan {
        val providers = mutableListOf<Provider>()
        val configs = mutableListOf<ModelConfig>()
        // 地址 → 服务商；"地址+型号" → 配置。两张索引保证同一件事不建两遍。
        val providerByUrl = mutableMapOf<String, Provider>()
        val configByKey = mutableMapOf<String, ModelConfig>()
        // 老配置里那条记着能力，搬过来别丢了 —— 那是人点过「测一下」验出来的。
        val abilityByKey = legacyProfiles.filter { !it.forgotten }.associateBy { key(it.baseUrl, it.model) }

        fun providerFor(url: String): Provider? {
            val u = url.trim()
            if (u.isEmpty()) return null
            providerByUrl[u]?.let { return it }
            val fresh = Provider(
                id = newId(),
                name = nameOf(u) ?: hostOf(u),
                baseUrl = u,
                // 老结构里没有"接口类型"这回事，一律按 OpenAI 兼容 ——
                // 这是唯一实现过的一种，也是绝大多数服务和中转站用的。
                kind = ApiKind.OpenAi,
            )
            providers += fresh
            providerByUrl[u] = fresh
            return fresh
        }

        fun configFor(url: String?, model: String?): ModelConfig? {
            val u = url?.trim().orEmpty()
            val m = model?.trim().orEmpty()
            if (u.isEmpty() || m.isEmpty()) return null
            configByKey[key(u, m)]?.let { return it }
            val provider = providerFor(u) ?: return null
            val legacy = abilityByKey[key(u, m)]
            val fresh = ModelConfig(
                id = newId(),
                providerId = provider.id,
                model = m,
                name = legacy?.name?.takeIf { it.isNotBlank() && it != m }.orEmpty(),
                capabilities = buildSet {
                    if (legacy?.directImage == true) add(Capability.Image)
                    if (legacy?.directVideo == true) add(Capability.Video)
                },
            )
            configs += fresh
            configByKey[key(u, m)] = fresh
            return fresh
        }

        // 先建"保存过的配置"：它们本身没有功能含义，但可能正好是待会儿要引用的那一条，
        // 先建出来就能命中索引，不会给同一个型号建两份。
        legacyProfiles.filter { !it.forgotten }.forEach { configFor(it.baseUrl, it.model) }

        // 聊天（也就是全局默认）—— 一定要有，它是"没配的功能"的落点。
        val chat = configFor(chatBaseUrl, chatModel)
        // 语音：开着才算。
        if (spokenOn) configFor(spokenBaseUrl, spokenModel)

        val routes = legacyFeatures.mapNotNull { (feature, picked) ->
            val config = configFor(picked.baseUrl, picked.model) ?: return@mapNotNull null
            feature.name to FeatureRoute(configId = config.id)
        }.toMap()

        return Plan(
            providers = providers,
            configs = configs,
            routes = routes,
            defaultConfigId = chat?.id.orEmpty(),
        )
    }

    /** 同一条配置的判断标准：地址（忽略大小写和结尾斜杠）+ 型号。 */
    private fun key(url: String, model: String) =
        url.trim().lowercase().trimEnd('/') + "\n" + model.trim()

    /** 地址里取个能看的名字当服务商名。 */
    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host.orEmpty().removePrefix("www.") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: url.trim()
}
