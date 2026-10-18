package com.cleo.cleos.games

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "game_session")
data class GameRow(@PrimaryKey val slot: Int = 1, val payload: String)

@Dao
interface GameDao {
    @Query("SELECT * FROM game_session WHERE slot = 1") fun observe(): Flow<GameRow?>
    @Query("SELECT * FROM game_session WHERE slot = 1") suspend fun get(): GameRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: GameRow)
    @Query("DELETE FROM game_session") suspend fun clear()
}

/** Independent optional game save; never migrates or rewrites the diary/chat database. */
@Database(entities = [GameRow::class], version = 1, exportSchema = false)
abstract class GameDatabase : RoomDatabase() {
    abstract fun games(): GameDao
}
