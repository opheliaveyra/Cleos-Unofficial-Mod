package com.cleo.cleos.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

/** Keys continue to live in SecretStore by address; profiles never duplicate credentials. */
@Serializable
data class ModelProfile(val id: String = UUID.randomUUID().toString(), val name: String,
                        val baseUrl: String, val model: String, val forgotten: Boolean = false,
                        val userSaved: Boolean? = null) {
    // The old release did not mark explicit saves. Preserve named entries and retain other
    // records in a separate recoverable list instead of guessing and deleting them.
    val isUserSaved: Boolean get() = userSaved ?: (name != model)
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
    data class Choice(val label: String, val where: String, val baseUrl: String, val model: String) {
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
    private suspend fun change(transform: (List<ModelProfile>) -> List<ModelProfile>) = mutex.withLock {
        write(ModelProfileRules.encode(transform(ModelProfileRules.decode(read().first()))))
    }
}
