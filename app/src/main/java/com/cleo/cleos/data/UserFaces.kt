package com.cleo.cleos.data

import com.cleo.cleos.data.db.ChatGroupEntity
import com.cleo.cleos.data.db.CompanionEntity

/**
 * 「我」的头像取哪一个 —— **全应用只从这里取**。
 *
 * 优先级：
 * - 单聊：**这个角色专属的** > 全局默认
 * - 群聊：**这个群专属的** > 全局默认 ——
 *   群没有"唯一对应的角色"，所以**不跟着当前发言的人变**（见需求第 7 条）
 *
 * 抽成一个对象，是为了别再出现"有的地方读全局、有的地方读专属"这种不一致。
 */
object UserFaces {
    /** 和 [ta] 聊天时，我这边的头像。 */
    fun forCompanion(ta: CompanionEntity?, settings: AppSettings): String? =
        ta?.userAvatar?.takeIf { it.isNotBlank() } ?: settings.userAvatar

    /** 在 [group] 里说话时，我这边的头像。 */
    fun forGroup(group: ChatGroupEntity?, settings: AppSettings): String? =
        group?.userAvatar?.takeIf { it.isNotBlank() } ?: settings.userAvatar
}
