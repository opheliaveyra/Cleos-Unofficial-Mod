package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 备份格式的兼容判断。
 *
 * **不再拿"备份里的 version > 本版的 VERSION"直接拒绝**。
 *
 * 原项目（上游）和二改版是同源代码，但备份格式的版本号是**两条谱系各自的数字**：
 * 上游最新一版写的是 `VERSION = 6`，二改版现在是 7（加了 MCP 字段）。
 * 数字大小不代表"新旧"，更不代表结构能不能读 —— 把它当单调量比，
 * 就会把本来能读的备份报成"来自更新版本"。
 *
 * 现在的规则：
 * - format 不是 `cleos-backup` → 不是我们的备份，拒绝
 * - version 读不出来 → 文件坏了，拒绝
 * - version <= 本版 → 正常导入
 * - version > 本版 → **仍然尝试导入**：字段都有默认值、未知字段会被忽略；
 *   给一句明确的 warning，不认识的字段跳过、数量以实际为准
 * - 结构真的对不上（必填字段缺失/类型变化）由 JSON 解析失败兜住 → 报"格式对不上"，
 *   而且**不动现有数据**（restoreFrom 本来就有快照 + 事务）
 */
object BackupCompat {
    /** 本版写出去时使用的格式版本（= [BackupFile.VERSION]）。 */
    const val WRITTEN = BackupFile.VERSION

    /** 从整份 JSON 里只读 format / version（不要求其余字段能解）。 */
    data class Header(val format: String?, val version: Int?)

    data class Verdict(val ok: Boolean, val warning: String? = null, val reason: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun header(raw: String): Header? = runCatching {
        val o = json.parseToJsonElement(raw) as? JsonObject ?: return null
        Header(
            format = (o["format"] as? JsonPrimitive)?.contentOrNull,
            version = ((o["version"] as? JsonPrimitive)?.contentOrNull)?.toIntOrNull(),
        )
    }.getOrNull()

    fun check(header: Header?): Verdict {
        if (header == null) return Verdict(false, reason = "备份文件坏了，读不出来")
        if (header.format != BackupFile.FORMAT) return Verdict(false, reason = "这不是 Cleos 的备份文件")
        val version = header.version ?: return Verdict(false, reason = "备份文件没有版本号，读不出来")
        return if (version <= WRITTEN) {
            Verdict(true)
        } else {
            Verdict(
                ok = true,
                warning = "这份备份的格式版本（$version）比这一版写出的（$WRITTEN）新。" +
                    "会尽量导入：不认识的内容会跳过，最后显示的数量以实际恢复的为准。",
            )
        }
    }
}
