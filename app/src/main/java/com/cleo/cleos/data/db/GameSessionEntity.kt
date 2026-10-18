package com.cleo.cleos.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 一局可恢复的棋（目前只有井字棋）。
 *
 * 本地规则仍是唯一权威：模型只给行动；这里只存"棋盘/回合/状态/revision"。
 * [pending] 为 true 表示保存时有一笔 TA 请求在途 —— 进程被杀后它不会自己回来，
 * 恢复时转成「已中断，可继续」，**不自动重试付费**。
 */
@Entity(tableName = "game_sessions")
data class GameSessionEntity(
    @PrimaryKey val id: String,
    val taId: Long,
    /** 9 个 0/1/2，逗号分隔：0 空、1 人（X）、2 TA（O）。 */
    val board: String,
    val turn: Int,
    val winner: Int,
    val moves: Int,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val pending: Boolean = false,
)

@Dao
interface GameSessionDao {
    @Query("SELECT * FROM game_sessions ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(): Flow<GameSessionEntity?>

    @Query("SELECT * FROM game_sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): GameSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: GameSessionEntity)

    @Query("DELETE FROM game_sessions WHERE id != :keep")
    suspend fun keepOnly(keep: String)

    @Query("DELETE FROM game_sessions")
    suspend fun clear()
}
