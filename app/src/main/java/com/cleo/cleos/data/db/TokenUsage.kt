package com.cleo.cleos.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * 一次模型请求的用量。**只记用量本身**：没有正文、没有 Key、没有附件。
 *
 * - [input] / [output] / [total] 是服务商自己报的数；服务商没给就是 "missing"（[source]），
 *   不去拿 0 或者本地猜的数字冒充。
 * - [cached] 和 [reasoning] 只在服务商给了明细时才有值。
 * - [scope] / [scopeId] / [scopeName]：`ta` 是单聊的某个角色，`group` 是某个群（[speaker] 记发言角色），
 *   `global` 是没有归属的后台请求。
 */
@Serializable
@Entity(tableName = "token_usage", indices = [Index("at"), Index("scopeId"), Index("model")])
data class TokenUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** 服务商：配置里的名字，取不到就用地址的 host。 */
    val provider: String,
    val model: String,
    /** ta / group / global */
    val scope: String,
    val scopeId: Long,
    val scopeName: String,
    /** 群聊里这次是哪个角色说的；单聊为空。 */
    val speaker: String = "",
    /** chat / group_chat / recap / memory / diary / vision / video / tool / feed / letter / probe */
    val purpose: String,
    val ok: Boolean,
    /** api（服务商给的）/ missing（服务商没给） */
    val source: String,
    val input: Long,
    val output: Long,
    val total: Long,
    val cached: Long? = null,
    val reasoning: Long? = null,
) {
    companion object {
        const val SOURCE_API = "api"
        const val SOURCE_MISSING = "missing"
        const val SCOPE_TA = "ta"
        const val SCOPE_GROUP = "group"
        const val SCOPE_GLOBAL = "global"
    }
}

@Dao
interface UsageDao {
    @androidx.room.Insert
    suspend fun add(row: TokenUsageEntity)

    /** [from] 含，[to] 不含。 */
    @Query("SELECT * FROM token_usage WHERE at >= :from AND at < :to ORDER BY at DESC LIMIT :limit")
    suspend fun range(from: Long, to: Long, limit: Int = 4000): List<TokenUsageEntity>

    @Query("SELECT * FROM token_usage ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<TokenUsageEntity>

    @Query("SELECT COUNT(*) FROM token_usage")
    suspend fun count(): Long

    @Query("DELETE FROM token_usage")
    suspend fun clear()

    @Query("DELETE FROM token_usage WHERE at < :before")
    suspend fun clearBefore(before: Long)
}
