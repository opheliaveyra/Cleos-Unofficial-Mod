package com.cleo.cleos.data

import com.cleo.cleos.data.db.Feature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条**服务商**：接口地址、接口类型，以及"密钥存在哪儿"。
 *
 * **密钥本身不在这里** —— 它按地址存在 [SecretStore]（安卓 Keystore 加密）。
 * 这个文件会被序列化进 DataStore，放密钥进去等于把锁和钥匙装一个信封里。
 *
 * 服务商和[ModelConfig]分开，是因为"一家"和"这家的哪个型号"本来就是两件事：
 * 换型号不该重填地址，换地址也不该把在这家配过的型号全丢掉。
 */
@Serializable
data class Provider(
    val id: String = newId(),
    /** 给人看的名字，比如「小米」「中转站」。 */
    val name: String = "",
    /** 接口地址（不带 /chat/completions 也行，发之前会规范）。 */
    val baseUrl: String = "",
    /** 这家是怎么说话的 —— 决定请求体长什么样，见 [ApiKind]。 */
    val kind: ApiKind = ApiKind.OpenAi,
)

/**
 * 服务商的**接口类型**。
 *
 * 现在只有 [OpenAi]（以及和它兼容的一大批中转服务）是通的；
 * 另外两种先摆在这儿：**能选、但发请求时会明确告诉你没实现**，
 * 而不是拿 OpenAI 的格式硬发过去、让人对着一个 400 猜半天。
 */
@Serializable
enum class ApiKind(val label: String) {
    /** OpenAI 兼容：`/chat/completions` + `messages`。绝大多数服务和中转站都是这个。 */
    OpenAi("OpenAI 兼容"),
    /** Anthropic 原生协议（`/v1/messages`）。**还没实现**。 */
    Anthropic("Anthropic"),
    /** Gemini 原生协议（`generateContent`）。**还没实现**。 */
    Gemini("Gemini"),
}

/**
 * 一条**模型配置**：某家服务商的某个型号，外加默认参数。
 *
 * 参数放这儿是"这条配置平时怎么用"；功能那边还能再**覆盖**（见 [FeatureRoute.overrides]）——
 * 比如总结想让它别太发散，就把温度压到 0.2，而不用为总结单独建一条配置。
 */
@Serializable
data class ModelConfig(
    val id: String = newId(),
    /** 这条属于哪个服务商。空了就是条坏配置，解析时会明说。 */
    val providerId: String = "",
    /** 型号 ID，比如 `deepseek-flash`、`mimo-v2.6-pro`。 */
    val model: String = "",
    /** 给自己看的名字；空着就用型号名。 */
    val name: String = "",
    /** 默认参数。 */
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    /**
     * 这个型号**证明过**的能力（[Capability.Image] / [Capability.Video]）。
     *
     * 和上一版一样：**没验过就是空**，靠"测一下"去探。不靠名字猜 ——
     * 名字里带 VL 而其实只会看图的、收到图静默丢掉的，都见过。
     */
    val capabilities: Set<Capability> = emptySet(),
    /** 删过的配置留个记号，不物理删 —— 已经写在历史消息里的引用还能查回来。 */
    val forgotten: Boolean = false,
) {
    val label: String get() = name.ifBlank { model }
}

/**
 * 参数覆盖：功能那层可以改模型配置里的默认值。
 *
 * 只放"确实要改的那几个"，null 表示"用配置里的默认"——
 * 这样以后给 [ModelConfig] 加参数，所有功能自动跟上，不用一条条补。
 */
@Serializable
data class ParamOverrides(
    val temperature: Float? = null,
    val maxTokens: Int? = null,
) {
    val isEmpty: Boolean get() = temperature == null && maxTokens == null
}

/** 一个功能绑到哪条模型配置上，以及要不要覆盖参数。 */
@Serializable
data class FeatureRoute(
    /** 空 = 没绑，**继承全局默认**。 */
    val configId: String = "",
    val overrides: ParamOverrides = ParamOverrides(),
)

/**
 * 整个配置：服务商、模型配置、功能绑定、全局默认。
 *
 * **一个文档一次写完**（`SecretStore` 里的一条 JSON）—— 分开存的话，
 * "删掉一个服务商"要把它的模型配置也清掉、还要把引用它们的功能退回默认，
 * 三步之间任何一步出错都会留下悬空的引用。
 */
@Serializable
data class ModelRegistryData(
    val providers: List<Provider> = emptyList(),
    val configs: List<ModelConfig> = emptyList(),
    /** 功能名（[Feature.name]）→ 它绑的那条配置。 */
    val routes: Map<String, FeatureRoute> = emptyMap(),
    /**
     * 全局默认用哪条配置。
     *
     * **没绑的功能继承它** —— 而不是"没有模型可用"。
     * 这是 [FeatureRoute.configId] 为空时的落点，也就是"聊天在用哪个"。
     */
    val defaultConfigId: String = "",
)

private fun newId() = java.util.UUID.randomUUID().toString()

/**
 * 配置的读写。
 *
 * 所有改动都过 [edit]：**读、改、写在一个锁里**，写完一次落盘。
 * 搬家和删服务商这类"要同时动好几处"的操作，也因此不会留下写到一半的状态。
 */
class ModelRegistry(private val secrets: SecretStore) {
    private val mutex = Mutex()

    val data: Flow<ModelRegistryData> = secrets.secret(KEY).map(::decode)

    suspend fun current(): ModelRegistryData = decode(secrets.secret(KEY).first())

    /**
     * 这个功能现在该用哪条 —— [ModelRoutes.resolve] 的便捷写法。
     *
     * 调用点想要的通常只是"给我一个能用的模型"，而不是"先读一份配置、再自己解析"。
     * 把读配置这一步包进来，六个调用点这才写得一样；**[Broken] 里带着那句人话**，
     * 调用方直接说给人听，不许自己换一个服务商顶上。
     */
    suspend fun route(feature: Feature): RouteResult = ModelRoutes.resolve(current(), feature)

    /** 读改写一次做完。 */
    suspend fun edit(transform: (ModelRegistryData) -> ModelRegistryData): ModelRegistryData = mutex.withLock {
        val before = decode(secrets.secret(KEY).first())
        val after = transform(before)
        if (after != before) secrets.setSecret(KEY, encode(after))
        after
    }

    // ---- 服务商 ----

    suspend fun addProvider(name: String, baseUrl: String, kind: ApiKind): Provider {
        val provider = Provider(name = name.trim(), baseUrl = baseUrl.trim(), kind = kind)
        edit { it.copy(providers = it.providers + provider) }
        return provider
    }

    /** 改了地址或类型；密钥不受影响（它按地址存）。 */
    suspend fun updateProvider(id: String, change: (Provider) -> Provider) = edit { data ->
        data.copy(providers = data.providers.map { if (it.id == id) change(it) else it })
    }

    /**
     * 删一个服务商，**连同它名下的模型配置一起**，并把引用它们的功能退回全局默认。
     *
     * 返回被牵连的功能，好让界面说一句"这几个功能回到默认了"——
     * 悄悄改掉别人的绑定比不删还糟。
     */
    suspend fun removeProvider(id: String): Set<Feature> {
        var affected = emptySet<Feature>()
        edit { data ->
            val doomed = data.configs.filter { it.providerId == id }.map { it.id }.toSet()
            val (routes, hit) = retarget(data.routes, doomed)
            affected = hit
            data.copy(
                providers = data.providers.filterNot { it.id == id },
                configs = data.configs.filterNot { it.providerId == id },
                routes = routes,
                defaultConfigId = data.defaultConfigId.takeIf { it !in doomed }.orEmpty(),
            )
        }
        return affected
    }

    // ---- 模型配置 ----

    suspend fun addConfig(providerId: String, model: String, name: String = ""): ModelConfig {
        val config = ModelConfig(providerId = providerId, model = model.trim(), name = name.trim())
        edit { data ->
            // 第一条配置自动成为全局默认 —— 不然装好 App 还得去点一下才有模型能用。
            data.copy(
                configs = data.configs + config,
                defaultConfigId = data.defaultConfigId.ifBlank { config.id },
            )
        }
        return config
    }

    suspend fun updateConfig(id: String, change: (ModelConfig) -> ModelConfig) = edit { data ->
        data.copy(configs = data.configs.map { if (it.id == id) change(it) else it })
    }

    /** 记下探测出来的能力（[Capability]）。**验过了才调这里**。 */
    suspend fun setCapability(id: String, capability: Capability, on: Boolean) = edit { data ->
        data.copy(configs = data.configs.map {
            if (it.id != id) it else it.copy(
                capabilities = if (on) it.capabilities + capability else it.capabilities - capability,
            )
        })
    }

    /** 删一条配置：功能退回全局默认，并返回被退回的那些。 */
    suspend fun removeConfig(id: String): Set<Feature> {
        var affected = emptySet<Feature>()
        edit { data ->
            val (routes, hit) = retarget(data.routes, setOf(id))
            affected = hit
            data.copy(
                configs = data.configs.filterNot { it.id == id },
                routes = routes,
                defaultConfigId = if (data.defaultConfigId == id) "" else data.defaultConfigId,
            )
        }
        return affected
    }

    // ---- 功能绑定 ----

    suspend fun bind(feature: Feature, configId: String, overrides: ParamOverrides = ParamOverrides()) = edit { data ->
        val routes = data.routes.toMutableMap()
        if (configId.isBlank()) routes.remove(feature.name) else routes[feature.name] = FeatureRoute(configId, overrides)
        data.copy(routes = routes)
    }

    suspend fun setDefault(configId: String) = edit { it.copy(defaultConfigId = configId) }

    /** 功能绑的那条；没绑返回空串（意思是"继承全局默认"）。 */
    fun boundConfigId(data: ModelRegistryData, feature: Feature): String =
        data.routes[feature.name]?.configId.orEmpty()

    /** 引用这几个配置的功能，全部退回全局默认，并把它们报出来。 */
    private fun retarget(routes: Map<String, FeatureRoute>, gone: Set<String>): Pair<Map<String, FeatureRoute>, Set<Feature>> =
        retargetRoutes(routes, gone)

    companion object {
        internal const val KEY = "model_registry"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        internal fun decode(raw: String?): ModelRegistryData {
            if (raw.isNullOrBlank()) return ModelRegistryData()
            return runCatching { json.decodeFromString<ModelRegistryData>(raw) }.getOrDefault(ModelRegistryData())
        }

        internal fun encode(data: ModelRegistryData): String = json.encodeToString(data)
    }
}

/**
 * 删掉配置时，把引用它的功能退回"没绑"（也就是继承全局默认）。
 *
 * 抽成顶层函数是为了能单独测：**这一步悄悄做错的话，人的绑定会莫名其妙变掉**，
 * 而这种错在界面上看不出来 —— 只会觉得"怎么它突然换模型了"。
 *
 * **参数覆盖留着不删**：重新绑一条时还能接着用，那是人调过的。
 */
internal fun retargetRoutes(
    routes: Map<String, FeatureRoute>,
    gone: Set<String>,
): Pair<Map<String, FeatureRoute>, Set<Feature>> {
    val hit = mutableSetOf<Feature>()
    val next = routes.toMutableMap()
    routes.forEach { (name, route) ->
        if (route.configId.isNotEmpty() && route.configId in gone) {
            Feature.entries.firstOrNull { it.name == name }?.let { hit += it }
            next[name] = route.copy(configId = "")
        }
    }
    return next to hit
}
