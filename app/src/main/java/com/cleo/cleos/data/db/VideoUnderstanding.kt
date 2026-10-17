package com.cleo.cleos.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * 一段视频被「视频理解」模型读出来的描述。
 *
 * 主键是（视频文件名 + 解析配置指纹）：**同样的视频、同样的模型，只解析一次**；
 * 换了模型或地址，指纹变了就重新解析。失败也存一条（[ok] = false），下次可以自动重试。
 */
@Serializable
@Entity(tableName = "video_understanding", primaryKeys = ["video", "config"])
data class VideoUnderstandingEntity(
    val video: String,
    /** `baseUrl|model` —— 换模型或换服务商就要重新读。 */
    val config: String,
    val ok: Boolean,
    val description: String = "",
    val error: String = "",
    val at: Long,
)

@Dao
interface VideoUnderstandingDao {
    @Query("SELECT * FROM video_understanding WHERE video = :video AND config = :config")
    suspend fun get(video: String, config: String): VideoUnderstandingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: VideoUnderstandingEntity)

    @Query("SELECT * FROM video_understanding")
    suspend fun all(): List<VideoUnderstandingEntity>

    @Query("DELETE FROM video_understanding WHERE video = :video")
    suspend fun deleteFor(video: String)
}
