package com.cleo.cleos.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema changes must come with a Migration. Never fall back to destructive migration:
 * this database holds a diary, and "the app updated and my diary is empty" is the one
 * failure this app cannot have.
 */
@Database(
    entities = [
        CompanionEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        DiaryEntryEntity::class,
        TodoEntity::class,
        LetterEntity::class,
        MemoryEntity::class,
        LaterEntity::class,
        WakeEntity::class,
        WakeActivityEntity::class,
        StickerEntity::class,
        FavoriteEntity::class,
        LoreEntity::class,
        FreeTopicStateEntity::class,
        FeedVisitStateEntity::class,
        FeedPostEntity::class,
        ChatGroupEntity::class,
        GroupMemberEntity::class,
        GroupTurnEntity::class,
        GroupMessageEntity::class,
        GroupMemoryEntity::class,
    ],
    version = 41,
    exportSchema = true,
    autoMigrations = [
        // 1 -> 2: tool calls on messages (four nullable columns, nothing rewritten).
        AutoMigration(from = 1, to = 2),
        // 2 -> 3: who wrote a diary entry, and whether it is a secret (both with defaults).
        AutoMigration(from = 2, to = 3),
        // 3 -> 4: pictures sent in the chat (one nullable column).
        AutoMigration(from = 3, to = 4),
        // 4 -> 5: several TAs. Their table; whose each conversation and TA diary entry is.
        // The first TA's row is made at startup from the old settings (Companions.ensure).
        AutoMigration(from = 4, to = 5, spec = AppDatabase.OneTaBefore::class),
        // 5 -> 6: letters (a table), and when each TA last tried to write one (a nullable column).
        AutoMigration(from = 5, to = 6),
        // 6 -> 7: what each TA keeps in mind (a table).
        AutoMigration(from = 6, to = 7),
        // 7 -> 8: when the reply to a letter comes, picked as it is sent (a nullable column).
        AutoMigration(from = 7, to = 8),
        // 8 -> 9: each conversation's recap and where it ends (nullable columns), and the
        // thinking switch on each TA (off to begin with).
        AutoMigration(from = 8, to = 9),
        // 9 -> 10: voice messages (a nullable column).
        AutoMigration(from = 9, to = 10),
        // 10 -> 11: what the TA thought before a reply, kept to be read (a nullable column).
        AutoMigration(from = 10, to = 11),
        // 11 -> 12: the message a message answers, for quoting (a nullable column).
        AutoMigration(from = 11, to = 12),
        // 12 -> 13: what TAs note to come back to, and what came of it (two tables); whether each
        // TA may reach out on its own (on to begin with); which messages it sent that way (none yet).
        AutoMigration(from = 12, to = 13),
        // 13 -> 14: the sticker collection (a table), and the emoji stuck on messages (a nullable column).
        AutoMigration(from = 13, to = 14),
        // 14 -> 15: which phone call a message was said in (a nullable column).
        AutoMigration(from = 14, to = 15),
        // 15 -> 16: a second model for each TA's words that are heard, on the phone and answering
        // voice messages (off to begin with, its address and model empty).
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
        // 17 -> 18: a TA's 设定, brought over from another app's world book (a table).
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        // Shared feed cards: one nullable column, preserving every existing message.
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 30, to = 31),
        AutoMigration(from = 31, to = 32),
        // Keep former members as historical authors while excluding them from new replies.
        AutoMigration(from = 32, to = 33),
        AutoMigration(from = 33, to = 34),
        AutoMigration(from = 34, to = 35),
        AutoMigration(from = 35, to = 36),
        // 36 -> 37: files sent in the chat (one nullable column). The file itself and the text
        // read out of it live in FileStore; the row keeps only what the chat shows.
        AutoMigration(from = 36, to = 37),
        // 37 -> 38: 每个功能各自指定模型（一个带默认值的 TEXT 列，空着就是跟着主模型走）。
        // 只是个字符串，里面装的是 JSON（FeatureModels）；解析不了就退回主模型，
        // 所以这一列的默认值本身不会影响任何已有数据。
        AutoMigration(from = 37, to = 38),
        // 38 -> 39: 模型配置从"存在 TA 身上"改成"独立一份、TA 只指向它"
        // （三个带默认值的 TEXT 列）。**只是加列，这一版不动任何已有数据** ——
        // 搬家在启动时做一次（见 ModelConfigs.ensure），那时候才有得测。
        AutoMigration(from = 38, to = 39),
        // 39 -> 40: 消息带上一起发出去的视频（一个带默认值的 TEXT 列，存 VideoStore 里的名字）。
        // **还是只加列** —— 视频本体在文件目录里，这一列只记"这条消息带了哪条视频"。
        AutoMigration(from = 39, to = 40),
        // 40 -> 41: 群里也能发语音（group_messages 上一个可空的 TEXT 列，存 MessageAudio 的 JSON）。
        // **只加列**：录音本体和私人聊天一样放在 ImageStore 的目录里，备份跟着一起走。
        AutoMigration(from = 40, to = 41),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun groups(): GroupDao
    abstract fun feed(): FeedDao
    abstract fun freeTopics(): FreeTopicDao
    abstract fun feedVisits(): FeedVisitDao
    abstract fun companions(): CompanionDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun diary(): DiaryDao
    abstract fun todos(): TodoDao
    abstract fun letters(): LetterDao
    abstract fun memories(): MemoryDao
    abstract fun later(): LaterDao
    abstract fun wakes(): WakeDao
    abstract fun wakeActivities(): WakeActivityDao
    abstract fun stickers(): StickerDao
    abstract fun favorites(): FavoriteDao
    abstract fun lore(): LoreDao

    /**
     * Before version 5 there was one TA, so every entry a TA wrote was TA 1's. (Conversations
     * get that from the column's default; a diary entry's owner depends on its author.)
     */
    class OneTaBefore : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE diary_entries SET companionId = 1 WHERE author = 'ai'")
        }
    }
}
