package com.cleo.cleos.data

import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.FeatureModels
import com.cleo.cleos.data.db.FeatureProfileIds
import kotlinx.coroutines.flow.first

/**
 * 把"模型设置存在 TA 身上"搬成"独立配置 + TA 只指向它"。
 *
 * **为什么要有这一步**：老的结构把同一件事散在四个地方 ——
 * TA 身上一对地址/型号（主模型）、一对（语音）、一份 JSON（功能，**抄的是副本**）、
 * 外加一份保存过的配置。副本之间不会互相同步，改一处别处不知道，
 * 这就是"存了之后就很难受"的根源。
 *
 * **跑一次就够**：三个 id 字段里有值就说明搬过了，直接跳过。
 * 所以它是幂等的 —— 启动几次都不会重复建配置。
 *
 * **为什么要单独一个类**：搬家逻辑（[ModelConfigMigration.plan]）是纯函数、能测；
 * 这里只负责"读数据 → 算计划 → 写回去"。把"怎么算"和"怎么存"分开，
 * 出问题时能一眼看出是哪一半错了。
 */
class ModelConfigs(
    private val db: AppDatabase,
    private val profiles: ModelProfiles,
) {
    /**
     * 把所有 TA 搬到新结构。已经搬过的跳过。
     *
     * 一个 TA 搬失败不影响别的：真出了什么岔子，**没搬的那几个还是老样子能用**，
     * 不至于整个 App 打不开（旧字段这一版都还留着）。
     */
    suspend fun ensure() {
        val all = db.companions().all()
        for (ta in all) {
            if (ta.chatProfileId.isNotBlank()) continue
            // 每次都重新读一遍已经有的：上一个 TA 刚建的，这个 TA 不该再建一份。
            val plan = runCatching { build(ta, profiles.profiles.first()) }.getOrNull() ?: continue
            if (plan.isEmpty) continue
            profiles.addAll(plan.create)
            // 用刚读到的 ta 直接写回去：这一步在启动时跑，没人跟它抢
            // （读-改-写之间不会有别的写入）。
            db.companions().update(
                ta.copy(
                    chatProfileId = plan.chatProfileId,
                    spokenProfileId = plan.spokenProfileId,
                    featureProfileIds = FeatureProfileIds.encode(plan.featureProfileIds),
                ),
            )
        }
    }

    private fun build(ta: CompanionEntity, existing: List<ModelProfile>) =
        ModelConfigMigration.plan(
            existing = existing,
            chatBaseUrl = ta.apiBaseUrl,
            chatModel = ta.apiModel,
            spokenOn = ta.spokenModelOn,
            spokenBaseUrl = ta.spokenApiBaseUrl,
            spokenModel = ta.spokenApiModel,
            legacyFeatures = FeatureModels.decode(ta.featureModels),
            nameOf = { url -> ApiPresets.at(url)?.name },
        )
}
