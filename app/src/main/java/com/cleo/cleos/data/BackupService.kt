package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.ai.ReplyWhen
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.data.db.FavoriteEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.data.db.LoreEntity
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.StickerEntity
import com.cleo.cleos.data.db.TodoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The settings in a backup. apiBaseUrl through knownSince described the one TA of before
 * there could be several; a newer backup fills them from the first TA and restores all of
 * them from [BackupFile.companions].
 */
@Serializable
data class BackupSettings(
    val feedInterests: String = "",
    val feedRssUrl: String = "",
    val feedNewsSources: String = "",
    val feedCover: String? = null,
    val feedBio: String = "",
    val apiBaseUrl: String,
    val apiModel: String,
    val aiName: String,
    val userName: String,
    val persona: String,
    val historySize: Int,
    val wallpaper: String?,
    val glassMode: String,
    val wallpaperDark: Boolean?,
    val wallpaperHue: Float?,
    val wallpaperChroma: Float?,
    val wallpaperTrough: Float? = null,
    val wallpaperPeak: Float? = null,
    /** JSON as SettingsRepository stores it; absent in backups from before the glass lab could apply. */
    val glassTuning: String? = null,
    /** Tool groups by name, comma-separated; absent in backups from before tools. */
    val tools: String? = null,
    val photoLibrary: String? = null,
    val feedPhotoTas: List<Long> = emptyList(),
    val selectedGroupId: Long? = null,
    val weatherCity: String = "",
    /** File names under images/; absent in backups from before the home page. */
    val userAvatar: String? = null,
    val aiAvatar: String? = null,
    val aiAvatarEmoji: String? = null,
    val chatAvatars: Boolean = true,
    val avatarEachMessage: Boolean = false,
    val myBubble: Int? = null,
    val myBubbleTheme: String = "glass",
    val bubblePaddingX: Int = 10,
    val bubblePaddingY: Int = 6,
    val messageSpacing: Int = BubbleSpacing.DEFAULT,
    val bubblePresets: List<BubblePreset> = emptyList(),
    val bubbleDecoration: BubbleDecoration = BubbleDecoration(),
    val bubbleBackgrounds: Map<String, BubbleBackground> = emptyMap(),
    val taBubbleThemes: Map<String, String> = emptyMap(),
    val knownSince: Long? = null,
    /** Absent from backups made before these could be set: the defaults then. */
    val letterReply: String? = null,
    val letterEveryDays: Int = 5,
    val voiceBaseUrl: String = "",
    val voiceModel: String = "",
    /** "api" for any OpenAI-shaped service in backups from before each had its own place (Speech.migrate). */
    val speechEngine: String = "",
    val speechVoices: Map<String, String> = emptyMap(),
    val minimaxGlobal: Boolean = false,
    val speechBaseUrl: String = "",
    val speechModel: String = "",
    val speechVoice: String = "",
    val elevenVoice: String = "",
    val elevenModel: String = "",
    val earVoice: Boolean = true,
    val expandVoiceText: Boolean = false,
)

/** The backup format: one zip, `backup.json` plus the pictures under `images/`. */
@Serializable
data class BackupFile(
    // No defaults on decode: a file without these is not one of ours.
    val format: String,
    val version: Int,
    val exportedAt: Long,
    val settings: BackupSettings,
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>,
    val diary: List<DiaryEntryEntity>,
    val todos: List<TodoEntity>,
    /** Absent in backups from before there could be several TAs. */
    val companions: List<CompanionEntity> = emptyList(),
    /** Absent in backups from before there were letters. */
    val letters: List<LetterEntity> = emptyList(),
    /** Absent in backups from before TAs kept memories. */
    val memories: List<MemoryEntity> = emptyList(),
    /** Absent in backups from before there were stickers; their pictures are under `images/` with the rest. */
    val stickers: List<StickerEntity> = emptyList(),
    val favorites: List<FavoriteEntity> = emptyList(),
    /** Absent in backups from before a TA had 设定 (a world book brought over from elsewhere). */
    val lore: List<LoreEntity> = emptyList(),
    val feedPosts: List<com.cleo.cleos.data.db.FeedPostEntity> = emptyList(),
    val groups: List<com.cleo.cleos.data.db.ChatGroupEntity> = emptyList(),
    val groupMembers: List<com.cleo.cleos.data.db.GroupMemberEntity> = emptyList(),
    val groupTurns: List<com.cleo.cleos.data.db.GroupTurnEntity> = emptyList(),
    val groupMessages: List<com.cleo.cleos.data.db.GroupMessageEntity> = emptyList(),
    val groupMemories: List<com.cleo.cleos.data.db.GroupMemoryEntity> = emptyList(),
    /**
     * 接的外部服务（MCP）。以前不进备份（地址里可能带 key、token 本身就是密钥）；
     * 现在按需求一起带走 —— 备份文件是给人自己保管的，恢复后服务列表和鉴权都还在。
     * 旧备份没有这一段：恢复时不动现有的服务。
     */
    val mcpServers: List<McpServer>? = null,
) {
    companion object {
        const val FORMAT = "cleos-backup"
        // Older readers must not re-activate former members or drop shared conversations.
        const val VERSION = 7
    }
}

/** 导入前的**只读**概览：解析、校验、数一数里面有什么，不写任何东西。 */
data class BackupPreview(
    val ok: Boolean,
    val error: String? = null,
    /** 兼容提示（格式比本版新等，见 [BackupCompat]）。 */
    val warning: String? = null,
    /** 「将恢复：…」那行。 */
    val counts: String? = null,
)

data class BackupSummary(
    val tas: Int,
    val conversations: Int,
    val messages: Int,
    val diary: Int,
    val letters: Int,
    val todos: Int,
    val images: Int,
    val voices: Int = 0,
    val stickers: Int = 0,
    val favorites: Int = 0,
    val lore: Int = 0,
    val feedPosts: Int = 0,
    val groups: Int = 0,
    val groupMessages: Int = 0,
    val groupMemories: Int = 0,
    /** 兼容提示（比如"备份格式比本版新，会尽量导入"）；正常导入时为 null。 */
    val note: String? = null,
) {
    override fun toString() =
        "$tas 个 TA、$conversations 段对话（$messages 条消息）、$diary 篇日记、$letters 封信、$todos 条待办、$images 张图" +
            (if (voices > 0) "、$voices 段语音" else "") +
            (if (stickers > 0) "、$stickers 个表情包" else "") +
            (if (favorites > 0) "、$favorites 条收藏" else "") +
            (if (lore > 0) "、$lore 条设定" else "") +
            (if (feedPosts > 0) "、$feedPosts 条动态" else "") +
            (if (groups > 0) "、$groups 个群聊（$groupMessages 条群消息）" else "") +
            (if (groupMemories > 0) "、$groupMemories 条群内记录" else "")
}

/** Recordings are named voice_…, among the pictures (VoiceRecorder). */
private const val VOICE_PREFIX = "voice_"

class BackupException(message: String) : Exception(message)

/**
 * Export and restore. The API key is never written out: it only exists encrypted with a
 * key that cannot leave this phone, and a backup file travels (chat apps, cloud drives).
 *
 * Restore replaces everything, so it is careful about order:
 *  1. read and check the whole file before touching anything;
 *  2. snapshot what is there now (`before-restore.zip`), so a wrong file can be undone;
 *  3. copy pictures in, then swap the database contents in one transaction. If any row
 *     is bad the transaction rolls back and the old data is still there.
 */
class BackupService(
    context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val images: ImageStore,
    /** Files sent in the chat, and the text read out of them: both travel with a backup. */
    private val files: FileStore,
    /** 接的外部服务（MCP）：现在也进备份。 */
    private val mcp: McpServers,
) {
    private val resolver = context.contentResolver
    private val cacheDir = context.cacheDir
    private val snapshot = File(context.filesDir, "backups/before-restore.zip")
    // encodeDefaults: format and version have default values, and without this they are
    // silently left out of the file, which makes the "is this our backup, which version"
    // check on restore pass for anything.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val hasSnapshot: Boolean get() = snapshot.exists()

    suspend fun export(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val out = resolver.openOutputStream(uri) ?: throw BackupException("打不开要保存的位置")
        out.use { write(it) }
    }

    suspend fun restore(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw BackupException("打不开这个文件")
        input.use { restoreFrom(it, takeSnapshot = true) }
    }

    /**
     * 导入前的只读概览：解析、校验、数一数里面有什么，**一个字都不写**。
     * 格式比本版新时带一句 warning（见 [BackupCompat]），而不是直接拒绝。
     */
    suspend fun preview(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        try {
            val input = resolver.openInputStream(uri) ?: return@withContext BackupPreview(false, error = "打不开这个文件")
            val (d, note) = input.use { readOnly(it) }
            BackupPreview(
                ok = true,
                warning = note,
                counts = "将恢复：${d.companions.size} 个 TA、" +
                    "${d.conversations.size} 段对话、${d.messages.size} 条消息、${d.diary.size} 篇日记、" +
                    "${d.memories.size} 条记忆、${d.groups.size} 个群聊、${d.groupMessages.size} 条群消息",
            )
        } catch (e: BackupException) {
            BackupPreview(false, error = e.message)
        } catch (e: Exception) {
            BackupPreview(false, error = e.message ?: "读不出来")
        }
    }

    /** 只读 JSON 条目并解析，不解图片、不写数据库。 */
    private fun readOnly(input: InputStream): Pair<BackupFile, String?> {
        var data: BackupFile? = null
        var note: String? = null
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == JSON_NAME) {
                    val raw = zip.readBytes().decodeToString()
                    val verdict = BackupCompat.check(BackupCompat.header(raw))
                    if (!verdict.ok) throw BackupException(verdict.reason ?: "这不是 Cleos 的备份文件")
                    note = verdict.warning
                    data = runCatching { json.decodeFromString<BackupFile>(raw) }
                        .getOrElse { throw BackupException("备份的格式和这一版对不上（${it.message?.take(120) ?: "读不出来"}）") }
                    break
                }
            }
        }
        val d = data ?: throw BackupException("这不是 Cleos 的备份文件")
        try { GroupBackupRules.validate(d.groups, d.groupMembers, d.groupTurns, d.groupMessages, d.groupMemories) }
        catch (e: IllegalArgumentException) { throw BackupException(e.message ?: "群聊备份不完整") }
        return d to note
    }

    /** Puts back what was there before the last restore. */
    suspend fun undoRestore(): BackupSummary = withContext(Dispatchers.IO) {
        if (!snapshot.exists()) throw BackupException("没有可以撤销的恢复")
        val summary = snapshot.inputStream().use { restoreFrom(it, takeSnapshot = false) }
        snapshot.delete()
        summary
    }

    private suspend fun write(raw: OutputStream): BackupSummary {
        val s = settings.current()
        val companions = db.companions().all()
        val lead = companions.firstOrNull()
        val data = db.withTransaction { BackupFile(
            format = BackupFile.FORMAT,
            version = BackupFile.VERSION,
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                apiBaseUrl = lead?.apiBaseUrl ?: ApiPresets.DeepSeek.baseUrl,
                selectedGroupId = settings.currentGroup.first(),
                apiModel = lead?.apiModel ?: ApiPresets.DeepSeek.defaultModel,
                aiName = lead?.name.orEmpty(),
                userName = s.userName,
                feedInterests = s.feedInterests,
                feedRssUrl = s.feedRssUrl,
                feedNewsSources = s.feedNewsSources,
                feedCover = s.feedCover,
                feedBio = s.feedBio,
                persona = lead?.persona.orEmpty(),
                historySize = s.historySize,
                wallpaper = s.wallpaper,
                glassMode = s.glassMode.name,
                wallpaperDark = s.wallpaperDark,
                wallpaperHue = s.wallpaperHue,
                wallpaperChroma = s.wallpaperChroma,
                wallpaperTrough = s.wallpaperTrough,
                wallpaperPeak = s.wallpaperPeak,
                glassTuning = s.glassTuning.takeIf { it.isNotEmpty() }?.let { encodeTuning(it) },
                tools = encodeTools(s.tools),
                photoLibrary = s.photoLibrary.name,
                feedPhotoTas = s.feedPhotoTas.sorted(),
                weatherCity = s.weatherCity,
                userAvatar = s.userAvatar,
                aiAvatar = lead?.avatar,
                aiAvatarEmoji = lead?.avatarEmoji,
                chatAvatars = s.chatAvatars,
                avatarEachMessage = s.avatarEachMessage,
                myBubble = s.myBubble,
                myBubbleTheme = s.myBubbleTheme,
                bubblePaddingX = s.bubblePaddingX,
                bubblePaddingY = s.bubblePaddingY,
                messageSpacing = s.messageSpacing,
                bubblePresets = s.bubblePresets,
                bubbleDecoration = s.bubbleDecoration,
                bubbleBackgrounds = s.bubbleBackgrounds,
                taBubbleThemes = s.taBubbleThemes,
                letterReply = s.letterReply.key,
                letterEveryDays = s.letterEveryDays,
                voiceBaseUrl = s.voiceBaseUrl,
                voiceModel = s.voiceModel,
                speechEngine = s.speechEngine,
                speechVoices = s.speechVoices,
                minimaxGlobal = s.minimaxGlobal,
                speechBaseUrl = s.speechBaseUrl,
                speechModel = s.speechModel,
                speechVoice = s.speechVoice,
                elevenVoice = s.elevenVoice,
                elevenModel = s.elevenModel,
                earVoice = s.earVoice,
                expandVoiceText = s.expandVoiceText,
                knownSince = lead?.knownSince,
            ),
            conversations = db.conversations().all(),
            messages = db.messages().all(),
            diary = db.diary().all(),
            todos = db.todos().all(),
            companions = companions,
            letters = db.letters().all(),
            memories = db.memories().all(),
            stickers = db.stickers().all(),
            favorites = db.favorites().all(),
            lore = db.lore().all(),
            feedPosts = db.feed().all(),
            groups = db.groups().allGroups(),
            groupMembers = db.groups().allMembers(),
            groupTurns = db.groups().allTurns(),
            groupMessages = db.groups().allMessages(),
            groupMemories = db.groups().allMemories(),
            mcpServers = mcp.current(),
        ) }
        val stickerFiles = data.stickers.map { it.file }.toSet()
        val favoriteFiles = data.favorites.flatMap { FavoriteContent.files(FavoriteContent.decode(it.parts)) }
        val pictures = (favoriteFiles + data.diary.flatMap { e -> DiaryBlocks.images(DiaryBlocks.decode(e.blocks)).map { it.file } } +
            data.feedPosts.flatMap { MessageImages.decode(it.images).map { picture -> picture.file } } +
            data.messages.flatMap { m -> MessageImages.decode(m.images).map { it.file } } +
            data.groupMessages.flatMap { m -> MessageImages.decode(m.images).map { it.file } } +
            // Recordings live with the pictures and travel the same way.
            data.messages.mapNotNull { m -> MessageAudios.decode(m.audio)?.file } +
            companions.mapNotNull { it.avatar } + data.groupMembers.mapNotNull { it.avatar } + data.groupMessages.mapNotNull { it.speakerAvatar } +
            // 「我」在不同角色 / 不同群里的专属头像也得一起打包，不然恢复后全是空的。
            companions.mapNotNull { it.userAvatar } + data.groups.mapNotNull { it.userAvatar } +
            stickerFiles +
            s.bubblePresets.flatMap { it.decoration.imageFiles() } + s.bubbleDecoration.components.mapNotNull { it.image } + listOfNotNull(s.wallpaper, s.feedCover, s.userAvatar, s.bubbleDecoration.faceImage, s.bubbleDecoration.starImage)).toSet()

        var written = 0
        var voices = 0
        var stickers = 0
        // The files the person sent, and the text taken out of each: without both, a restored
        // message would name a file the TA can no longer read.
        val attachments = data.messages.flatMap { m -> MessageFiles.decode(m.files) }.map { it.file }.toSet()
        ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_NAME))
            zip.write(json.encodeToString(data).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (name in pictures) {
                val f = images.file(name)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("images/$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                when {
                    name.startsWith(VOICE_PREFIX) -> voices++
                    name in stickerFiles -> stickers++
                    else -> written++
                }
            }
            for (name in attachments + attachments.map { FileStore.textName(it) }) {
                val f = files.file(name)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("files/$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return BackupSummary(
            companions.size, data.conversations.size, data.messages.size, data.diary.size, data.letters.size, data.todos.size,
            written, voices, stickers, data.favorites.size, data.lore.size, data.feedPosts.size,
            groups = data.groups.size, groupMessages = data.groupMessages.count { it.content.isNotBlank() || it.images != null },
            groupMemories = data.groupMemories.size,
        )
    }

    private suspend fun restoreFrom(input: InputStream, takeSnapshot: Boolean): BackupSummary {
        val staging = File(cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        // Files are staged apart from pictures: both are named by bare file name in the archive,
        // and a file whose text is read back must not be mistaken for a picture.
        val stagingFiles = File(cacheDir, "restore-files-${System.nanoTime()}").apply { mkdirs() }
        try {
            var data: BackupFile? = null
            /** 版本兼容提示：格式比本版新时带一句，最后随摘要给出去（见 BackupCompat）。 */
            var note: String? = null
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == JSON_NAME -> {
                            val raw = zip.readBytes().decodeToString()
                            // 先只读 format/version 判断兼容（见 BackupCompat）——**不拿数字大小直接拒绝**。
                            val verdict = BackupCompat.check(BackupCompat.header(raw))
                            if (!verdict.ok) throw BackupException(verdict.reason ?: "这不是 Cleos 的备份文件")
                            note = verdict.warning
                            data = runCatching { json.decodeFromString<BackupFile>(raw) }
                                .getOrElse {
                                    throw BackupException(
                                        "备份的格式和这一版对不上（${it.message?.take(120) ?: "读不出来"}）。没有改动现有的数据。",
                                    )
                                }
                        }
                        entry.name.startsWith("images/") && !entry.isDirectory -> {
                            // Only the bare file name is used: a crafted archive with
                            // "images/../../somewhere" must not write outside the staging dir.
                            val name = File(entry.name).name
                            if (name.isNotBlank() && !name.startsWith(".")) {
                                File(staging, name).outputStream().use { zip.copyTo(it) }
                            }
                        }
                        entry.name.startsWith("files/") && !entry.isDirectory -> {
                            val name = File(entry.name).name
                            if (name.isNotBlank() && !name.startsWith(".")) {
                                File(stagingFiles, name).outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                }
            }
            val d = data ?: throw BackupException("这不是 Cleos 的备份文件")
            if (d.format != BackupFile.FORMAT) throw BackupException("这不是 Cleos 的备份文件")
            // 注意：这里**不再**比较 d.version > BackupFile.VERSION —— 两条谱系的版本号不可比，
            // 判断挪到了 BackupCompat（新格式先尝试导入，不直接拒绝）。
            try { GroupBackupRules.validate(d.groups, d.groupMembers, d.groupTurns, d.groupMessages, d.groupMemories) }
            catch (e: IllegalArgumentException) { throw BackupException(e.message ?: "群聊备份不完整") }

            if (takeSnapshot) {
                snapshot.parentFile?.mkdirs()
                val tmp = File(snapshot.path + ".tmp")
                tmp.outputStream().use { write(it) }
                tmp.renameTo(snapshot)
            }

            val pictures = staging.listFiles().orEmpty()
            for (f in pictures) {
                val dest = images.file(f.name)
                if (!dest.exists()) f.copyTo(dest)
            }
            for (f in stagingFiles.listFiles().orEmpty()) {
                val dest = files.file(f.name)
                if (!dest.exists()) f.copyTo(dest)
            }
            val bs = d.settings
            fun picture(name: String?) = name?.takeIf { images.file(it).exists() }
            // A backup from before there could be several TAs has one, described in its settings.
            val companions = d.companions.ifEmpty {
                listOf(
                    CompanionEntity(
                        id = Companions.FIRST,
                        name = bs.aiName,
                        persona = bs.persona,
                        apiBaseUrl = bs.apiBaseUrl,
                        apiModel = bs.apiModel,
                        avatar = bs.aiAvatar,
                        avatarEmoji = bs.aiAvatarEmoji,
                        knownSince = bs.knownSince,
                        createdAt = d.exportedAt,
                    ),
                )
            }.map { it.copy(avatar = picture(it.avatar), userAvatar = picture(it.userAvatar)) }
            // TA entries in such a backup carry no owner: they were all TA 1's.
            val diary = d.diary.map {
                if (it.author == DiaryEntryEntity.AUTHOR_AI && it.companionId == null) it.copy(companionId = Companions.FIRST) else it
            }
            // A sticker is its picture: one whose file didn't come along would be a name drawn as nothing.
            val stickers = d.stickers.filter { images.file(it.file).exists() }
            db.withTransaction {
                // What TAs noted to come back to, and what came of it: not in backups, and about
                // conversations that are about to go.
                db.favorites().clear()
                db.groups().clear()
                db.later().clear()
                db.wakes().clear()
                db.messages().clear()
                db.conversations().clear()
                db.diary().clear()
                db.todos().clear()
                db.letters().clear()
                db.memories().clear()
                db.lore().clear()
                db.feed().clear()
                db.stickers().clear()
                db.companions().clear()
                db.companions().insertAll(companions)
                db.conversations().insertAll(d.conversations)
                db.messages().insertAll(d.messages)
                db.diary().insertAll(diary)
                db.todos().insertAll(d.todos)
                db.letters().insertAll(d.letters)
                db.memories().insertAll(d.memories)
                db.lore().insertAll(d.lore)
                db.feed().insertAll(d.feedPosts)
                db.stickers().insertAll(stickers)
                db.favorites().insertAll(d.favorites)
                // 群专属的「我」的头像也要把旧文件名换成恢复后的文件名。
            db.groups().restoreGroups(d.groups.map { it.copy(userAvatar = picture(it.userAvatar)) })
                db.groups().insertMembers(d.groupMembers.map { it.copy(avatar = picture(it.avatar)) })
                db.groups().restoreTurns(d.groupTurns.map(GroupBackupRules::restored))
                db.groups().restoreMessages(d.groupMessages.map { com.cleo.cleos.ai.GroupPhotoRules.restored(GroupBackupRules.restored(it)) { name -> picture(name) != null }.copy(speakerAvatar = picture(it.speakerAvatar)) })
                db.groups().restoreMemories(d.groupMemories)
            // 外部服务：备份里有这一段就整体换回来；旧备份没有，则不动现在这套。
            d.mcpServers?.let { mcp.replaceAll(it) }
            }
            // A backup from before each voice service had its own place says "api" for all of them.
            val (speechEngine, speechVoices) = Speech.migrate(bs.speechEngine, bs.speechBaseUrl, bs.speechVoice, bs.speechVoices)
            settings.update {
                it.copy(
                    userName = bs.userName,
                    feedInterests = bs.feedInterests,
                    feedRssUrl = bs.feedRssUrl,
                    feedNewsSources = bs.feedNewsSources,
                    feedCover = bs.feedCover?.takeIf { images.file(it).exists() },
                    feedBio = bs.feedBio.take(120),
                    historySize = bs.historySize,
                    wallpaper = bs.wallpaper?.takeIf { name -> images.file(name).exists() },
                    glassMode = runCatching { GlassMode.valueOf(bs.glassMode) }.getOrDefault(GlassMode.Auto),
                    wallpaperDark = bs.wallpaperDark,
                    wallpaperHue = bs.wallpaperHue,
                    wallpaperChroma = bs.wallpaperChroma,
                    wallpaperTrough = bs.wallpaperTrough,
                    wallpaperPeak = bs.wallpaperPeak,
                    glassTuning = decodeTuning(bs.glassTuning),
                    tools = bs.tools?.let(::decodeTools) ?: AppSettings().tools,
                    photoLibrary = PhotoLibrary.decode(bs.photoLibrary),
                    feedPhotoTas = bs.feedPhotoTas.filter { id -> companions.any { ta -> ta.id == id } }.toSet(),
                    weatherCity = bs.weatherCity,
                    userAvatar = picture(bs.userAvatar),
                    chatAvatars = bs.chatAvatars,
                    avatarEachMessage = bs.avatarEachMessage,
                    myBubble = bs.myBubble,
                    myBubbleTheme = bs.myBubbleTheme,
                    bubblePaddingX = bs.bubblePaddingX.coerceIn(6, 24),
                    bubblePaddingY = bs.bubblePaddingY.coerceIn(4, 16),
                    messageSpacing = BubbleSpacing.normalized(bs.messageSpacing),
                    bubblePresets = bs.bubblePresets.distinctBy { it.id }.take(50).map { it.normalized().let { p -> p.copy(decoration = p.decoration.mapImages(::picture)) } },
                    bubbleDecoration = bs.bubbleDecoration.normalized().let { it.copy(faceImage = picture(it.faceImage), starImage = picture(it.starImage), components = it.components.map { item -> item.copy(image = picture(item.image)) }).normalized() },
                    bubbleBackgrounds = bs.bubbleBackgrounds.mapValues { it.value.normalized() },
                    taBubbleThemes = bs.taBubbleThemes.filterKeys { key -> companions.any { ta -> ta.id.toString() == key } },
                    letterReply = ReplyWhen.of(bs.letterReply),
                    letterEveryDays = bs.letterEveryDays,
                    voiceBaseUrl = bs.voiceBaseUrl,
                    voiceModel = bs.voiceModel,
                    speechEngine = speechEngine,
                    speechVoices = speechVoices,
                    minimaxGlobal = bs.minimaxGlobal,
                    speechBaseUrl = bs.speechBaseUrl,
                    speechModel = bs.speechModel,
                    speechVoice = bs.speechVoice,
                    elevenVoice = bs.elevenVoice,
                    elevenModel = bs.elevenModel,
                    earVoice = bs.earVoice,
                    expandVoiceText = bs.expandVoiceText,
                )
            }
            settings.setCurrentCompanion(companions.first().id)
            settings.setCurrentConversation(null)
            settings.setCurrentGroup(bs.selectedGroupId?.takeIf { id -> d.groups.any { it.id == id } })
            return BackupSummary(
                companions.size, d.conversations.size, d.messages.size, d.diary.size, d.letters.size, d.todos.size,
                pictures.count { !it.name.startsWith(VOICE_PREFIX) && it.name !in stickers.map { s -> s.file }.toSet() },
                voices = pictures.count { it.name.startsWith(VOICE_PREFIX) },
                stickers = stickers.size, favorites = d.favorites.size, lore = d.lore.size, feedPosts = d.feedPosts.size,
                groups = d.groups.size, groupMessages = d.groupMessages.count { it.content.isNotBlank() || it.images != null },
                groupMemories = d.groupMemories.size,
                note = note,
            )
        } finally {
            staging.deleteRecursively()
            stagingFiles.deleteRecursively()
        }
    }

    private companion object {
        const val JSON_NAME = "backup.json"
    }
}
