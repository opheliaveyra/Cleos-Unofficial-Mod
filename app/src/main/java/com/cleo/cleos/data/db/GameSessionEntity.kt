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
 * 一局可恢复的棋（井字棋 / 五子棋）。
 *
 * [gameType] 区分游戏：每种游戏各自保留最近一局，**互不覆盖**。
 * [pending] 为 true 表示保存时有一笔 TA 请求在途 —— 进程被杀后恢复时转成「已中断，可继续」。
 */
@Entity(tableName = "game_sessions")
data class GameSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "'tic_tac_toe'") val gameType: String,
    val taId: Long,
    /** 棋盘：井字棋 9 个 0/1/2；五子棋 225 个 0/1/2（逗号分隔）。 */
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
    @Query("SELECT * FROM game_sessions WHERE gameType = :gameType ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(gameType: String): Flow<GameSessionEntity?>

    @Query("SELECT * FROM game_sessions WHERE gameType = :gameType ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(gameType: String): GameSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: GameSessionEntity)

    /** 只保留同一游戏类型的这一局；别的游戏不受影响。 */
    @Query("DELETE FROM game_sessions WHERE gameType = :gameType AND id != :keep")
    suspend fun keepOnly(gameType: String, keep: String)

    @Query("DELETE FROM game_sessions WHERE gameType = :gameType")
    suspend fun clear(gameType: String)

    @Query("DELETE FROM game_sessions")
    suspend fun clearAll()
}
