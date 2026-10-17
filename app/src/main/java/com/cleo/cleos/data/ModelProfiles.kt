package com.cleo.cleos.data

import com.cleo.cleos.data.db.Feature
import com.cleo.cleos.data.db.FeatureModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

/**
 * 一条模型配置：用谁家的哪个型号。
 *
 * **能力是"声明"，不是"指派"。** 下面那几个 `direct*` 开关说的是
 * **"这个模型自己能直接吃这种媒体"**，和"你把它派给了哪个功能"是两件事。
 *
 * 这一点是从 Operit 学来的（`ModelConfigData` 里的 `enableDirectImageProcessing` 等），
 * 而且它**默认全是 false** —— 没证明过的能力一律当作没有。
 *
 * 玖玖遇到的问题正是这个：她在「图像识别」配了一个模型，
 * 但那条配置上没声明"我能看图"，于是调度那边判断"没配识图服务"，图就没送过去。
 */
@Serializable
data class ModelProfile(val id: String = UUID.randomUUID().toString(), val name: String,
                        val baseUrl: String, val model: String, val forgotten: Boolean = false,
                        val userSaved: Boolean? = null,
                        /**
                         * 能直接看图吗。
                         *
                         * **默认 false** —— 由设置页那个「测试」按钮探出来才置真：
                         * 发一张印着随机暗号的图让它只回暗号，答对了才算会看。
                         * 猜出来的"它会看"不算数（GPT 全系收到图会静默丢掉还回一段编的）。
                         */
                        val directImage: Boolean = false,
                        /** 能直接看视频吗（`video_url` + base64）。同样由探针确定。 */
                        val directVideo: Boolean = false) {
    // The old release did not mark explicit saves. Preserve named entries and retain other
    // records in a separate recoverable list instead of guessing and deleting them.
    val isUserSaved: Boolean get() = userSaved ?: (name != model)
}

/**
 * 一个功能**需要**什么能力。
 *
 * 这是"能力调度"的另一半：一边是配置声明"我有什么"，一边是功能声明"我要什么"，
 * 两边对不上就说明**这个功能配错了**，界面要当场说出来。
 */
enum class Capability(val label: String) {
    /** 能直接看图。 */
    Image("看图"),
    /** 能直接看视频。 */
    Video("看视频"),
}

/** 每个功能需要的能力；没列的功能不需要特殊能力（能聊天就行）。 */
val Feature.needs: Set<Capability>
    get() = when (this) {
        Feature.Vision -> setOf(Capability.Image)
        Feature.Video -> setOf(Capability.Video)
        else -> emptySet()
    }

/** 这条配置有没有某个能力。 */
fun ModelProfile.has(capability: Capability): Boolean = when (capability) {
    Capability.Image -> directImage
    Capability.Video -> directVideo
}

/** 记下探测出来的能力。只有**验过了**才这么改（见 `CapabilityTester`）。 */
fun ModelProfile.with(capability: Capability, value: Boolean): ModelProfile = when (capability) {
    Capability.Image -> copy(directImage = value)
    Capability.Video -> copy(directVideo = value)
}

/**
 * 功能指向的配置缺不缺能力。
 *
 * 返回缺的那些；空集表示没问题。**界面上要把结果画出来** ——
 * 配了一个不会看图的模型给「图像识别」，就该当场警告，
 * 而不是等人发了图发现没用、再回来一个字段一个字段地猜。
 */
fun missingCapabilities(feature: Feature, picked: ModelProfile?): Set<Capability> {
    val need = feature.needs
    if (need.isEmpty() || picked == null) return emptySet()
    return need.filterNot { picked.has(it) }.toSet()
}

/**
 * 从旧结构搬到新结构。
 *
 * 旧的样子：**同一个东西存在四个地方、互相抄** ——
 * TA 身上一对 `apiBaseUrl`/`apiModel`（主模型）、一对 `spokenApi*`（语音）、
 * 一份 `featureModels` JSON（功能，**抄的是地址和型号的副本**），
 * 外加一份 `ModelProfiles`（保存过的）。改一处，别处不知道。
 *
 * 新的样子：**配置只有一份**（[ModelProfile]），别处只存**指向它的 id**。
 * 这个对象负责算出"该建哪些配置、谁指向谁"。
 *
 * **抽成纯函数是因为这件事写错就没法回头**：它直接动的是人已经在用的数据。
 * 所以"该建什么、指向谁"这一步单独拎出来测，不跟数据库搅在一起。
 */
object ModelConfigMigration {
    /** 搬迁计划：要新建哪些配置、谁指向谁。 */
    data class Plan(
        /** 需要新建的配置（已经有的不会重复建）。 */
        val create: List<ModelProfile>,
        /** 主模型指向哪条。 */
        val chatProfileId: String,
        /** 语音模型指向哪条；没开、或者没填全，就是空。 */
        val spokenProfileId: String,
        /** 每个功能指向哪条（只含真正配过、能用的那些）。 */
        val featureProfileIds: Map<Feature, String>,
    ) {
        val isEmpty: Boolean get() = create.isEmpty() && chatProfileId.isBlank() && spokenProfileId.isBlank() && featureProfileIds.isEmpty()
    }

    /**
     * 算出搬迁计划。
     *
     * @param existing 已经保存过的配置 —— 用来避免重复建同一件事
     * @param legacyFeatures 旧的 `featureModels` JSON（地址 + 型号），里面每条都要变成"指向"
     * @param nameOf 地址 → 好听的名字（比如 https://api.deepseek.com → DeepSeek）；
     *               不传就用型号本身当名字
     * @param newId 生成新 id；传进来是为了纯函数能测（不然每次跑都不同，没法断言）
     */
    fun plan(
        existing: List<ModelProfile>,
        chatBaseUrl: String?,
        chatModel: String?,
        spokenOn: Boolean,
        spokenBaseUrl: String?,
        spokenModel: String?,
        legacyFeatures: Map<Feature, FeatureModel>,
        nameOf: (String) -> String? = { null },
        newId: () -> String = { java.util.UUID.randomUUID().toString() },
    ): Plan {
        val create = mutableListOf<ModelProfile>()
        // 已经有的按"地址 + 型号"索引：同一件事不建第二遍。
        val byAddress = existing.filter { !it.forgotten }.associateBy { key(it.baseUrl, it.model) }.toMutableMap()

        fun take(url: String?, model: String?): String {
            val u = url?.trim().orEmpty()
            val m = model?.trim().orEmpty()
            if (u.isEmpty() || m.isEmpty()) return ""
            byAddress[key(u, m)]?.let { return it.id }
            val fresh = ModelProfile(id = newId(), name = nameOf(u) ?: m, baseUrl = u, model = m, userSaved = true)
            create += fresh
            byAddress[key(u, m)] = fresh
            return fresh.id
        }

        val chatId = take(chatBaseUrl, chatModel)
        val spokenId = if (spokenOn) take(spokenBaseUrl, spokenModel) else ""
        val features = legacyFeatures.mapNotNull { (feature, picked) ->
            val id = take(picked.baseUrl, picked.model)
            if (id.isEmpty()) null else feature to id
        }.toMap()

        return Plan(create, chatId, spokenId, features)
    }

    private fun key(url: String, model: String) = url.trim().lowercase().trimEnd('/') + "\n" + model.trim()
}

/**
 * 切换模型时能选的东西。
 *
 * **从实际在用的出发，不是从"手动保存过的"出发。** 这条是踩过坑才明白的：
 * 原来只列保存过的配置，结果主模型明明配好了、这里却是空的 —— 人还得回去再填一遍地址，
 * 那跟没做一样。
 *
 * 参考 RikkaHub 的做法（它的助手文档原话）："**模型覆盖**：设置聊天模型将此助手固定到
 * 特定的**提供商和模型**。留空则使用当前选定的全局默认模型。" —— 提供商是全局的，
 * 每个地方只挑"用谁家的哪个型号"。
 *
 * 抽成纯函数是为了能单独测：去重、顺序、空值这些都不该靠界面来猜。
 */
object ModelChoices {
    /** 一项：叫它什么、它是哪来的、地址和型号。 */
    data class Choice(
        val label: String,
        val where: String,
        val baseUrl: String,
        val model: String,
        /** 三层结构里那条配置的 id；老式构造（[of]）不填，空串。 */
        val id: String = "",
    ) {
        /** 某个功能现在配的是不是就是这一项。 */
        fun sameAs(picked: com.cleo.cleos.data.db.FeatureModel?) =
            picked != null && picked.baseUrl == baseUrl && picked.model == model
    }

    /** 主模型那一项的描述，界面上标成"主模型正在用"。 */
    const val WHERE_CHAT = "主模型正在用"
    const val WHERE_SPOKEN = "电话和语音在用"
    const val WHERE_SAVED = "保存过的配置"

    /**
     * 按 主模型 → 语音模型 → 保存过的配置 排出来，**按"地址 + 型号"去重**。
     *
     * 地址或型号空着的一律不列：列出来点了也没用，那种配置发出去只会报错。
     */
    fun of(
        chatBaseUrl: String?,
        chatModel: String?,
        spokenOn: Boolean,
        spokenBaseUrl: String?,
        spokenModel: String?,
        profiles: List<ModelProfile>,
        nameOf: (String) -> String? = { null },
    ): List<Choice> {
        val out = mutableListOf<Choice>()
        fun add(label: String, where: String, url: String?, model: String?) {
            val u = url?.trim().orEmpty()
            val m = model?.trim().orEmpty()
            if (u.isEmpty() || m.isEmpty()) return
            if (out.any { it.baseUrl == u && it.model == m }) return
            out += Choice(label, where, u, m)
        }
        add(nameOf(chatBaseUrl.orEmpty()) ?: "主模型", WHERE_CHAT, chatBaseUrl, chatModel)
        if (spokenOn) add(nameOf(spokenBaseUrl.orEmpty()) ?: "语音模型", WHERE_SPOKEN, spokenBaseUrl, spokenModel)
        profiles.filter { !it.forgotten }.forEach { p ->
            add(p.name.ifBlank { p.model }, WHERE_SAVED, p.baseUrl, p.model)
        }
        return out
    }
}

internal object ModelProfileRules {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(profiles: List<ModelProfile>) = json.encodeToString(profiles)
    fun decode(raw: String?): List<ModelProfile> = if (raw.isNullOrBlank()) emptyList()
        else json.decodeFromString<List<ModelProfile>>(raw)
    fun valid(url: String, model: String): Boolean = runCatching {
        val uri = URI(url.trim())
        uri.scheme.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && model.isNotBlank()
    }.getOrDefault(false)
    fun matches(profile: ModelProfile, url: String, model: String) =
        addressOf(profile.baseUrl) == addressOf(url.trim()) && profile.model == model.trim()
    fun saved(profiles: List<ModelProfile>, url: String, model: String, name: String?): List<ModelProfile> {
        require(valid(url, model)) { "先填好接口地址和模型名称" }
        val existing = profiles.firstOrNull { matches(it, url, model) }
        // An explicit removal must survive automatic history collection and app restarts.
        // Saving by name deliberately restores that connection to the list.
        if (existing?.forgotten == true && name == null) return profiles
        val label = name?.trim()?.take(64)?.takeIf { it.isNotEmpty() } ?: existing?.name ?: model.trim().take(64)
        val profile = ModelProfile(existing?.id ?: UUID.randomUUID().toString(), label, url.trim(), model.trim(),
            userSaved = name != null || existing?.isUserSaved == true)
        return listOf(profile) + profiles.filterNot { it.id == profile.id }
    }
}

class ModelProfiles(private val read: () -> Flow<String?>, private val write: suspend (String) -> Unit) {
    constructor(secrets: SecretStore) : this({ secrets.secret("model_profiles") }, { secrets.setSecret("model_profiles", it) })
    private val mutex = Mutex()
    val profiles: Flow<List<ModelProfile>> = read().map { ModelProfileRules.decode(it).filter { p -> !p.forgotten && p.isUserSaved } }
    val legacy: Flow<List<ModelProfile>> = read().map { ModelProfileRules.decode(it).filter { p -> !p.forgotten && !p.isUserSaved } }
    suspend fun save(url: String, model: String, name: String? = null) = change {
        ModelProfileRules.saved(it, url, model, name)
    }
    suspend fun rename(id: String, name: String) {
        require(name.isNotBlank()) { "名称不能为空" }
        change { profiles -> profiles.map { if (it.id == id) it.copy(name = name.trim().take(64), userSaved = true) else it } }
    }
    suspend fun delete(id: String) = change { profiles -> profiles.map { if (it.id == id) it.copy(forgotten = true) else it } }

    /**
     * 一次加一批 —— 搬家（[ModelConfigMigration]）要一次建好几条。
     * 一条一条调 [save] 会来回解码编码好几遍，也没法保证是一次写。
     */
    suspend fun addAll(added: List<ModelProfile>) {
        if (added.isEmpty()) return
        change { profiles ->
            val have = profiles.map { it.id }.toSet()
            profiles + added.filter { it.id !in have }
        }
    }

    /**
     * 记下探测出来的能力。
     *
     * **只有验过了才走到这里**（见 `CapabilityTester`）——
     * 能力不是"我猜它会"，是"它答对了那道只有真会看才答得出的题"。
     */
    suspend fun setCapability(id: String, capability: Capability, value: Boolean) = change { profiles ->
        profiles.map { if (it.id == id) it.with(capability, value) else it }
    }

    private suspend fun change(transform: (List<ModelProfile>) -> List<ModelProfile>) = mutex.withLock {
        write(ModelProfileRules.encode(transform(ModelProfileRules.decode(read().first()))))
    }
}
