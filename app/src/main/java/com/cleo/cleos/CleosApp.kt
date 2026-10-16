package com.cleo.cleos

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.room.Room
import androidx.room.withTransaction
import com.cleo.cleos.ai.AiSelfAvatar
import com.cleo.cleos.ai.Calls
import com.cleo.cleos.ai.ChatClient
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.EarVoice
import com.cleo.cleos.ai.Glance
import com.cleo.cleos.ai.Later
import com.cleo.cleos.ai.Letters
import com.cleo.cleos.ai.Listening
import com.cleo.cleos.ai.Lyrics
import com.cleo.cleos.ai.PersonaMemory
import com.cleo.cleos.ai.PhoneCalendar
import com.cleo.cleos.ai.PhoneClock
import com.cleo.cleos.ai.PhoneLocation
import com.cleo.cleos.ai.PhoneMusic
import com.cleo.cleos.ai.McpClient
import com.cleo.cleos.ai.McpHub
import com.cleo.cleos.ai.Recaps
import com.cleo.cleos.ai.OpenMeteo
import com.cleo.cleos.ai.SecretRequests
import com.cleo.cleos.ai.Speaker
import com.cleo.cleos.ai.ToolBox
import com.cleo.cleos.ai.Transcriber
import com.cleo.cleos.data.BackupService
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.ForeignImport
import com.cleo.cleos.data.FileStore
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.McpServers
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.Stickers
import com.cleo.cleos.data.VideoFrames
import com.cleo.cleos.data.Workspace
import com.cleo.cleos.data.db.AppDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class CleosApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Before anything that could fail: a crash while the rest is set up is kept too.
        CrashLog.install(this)
        container = AppContainer(this)
        container.notifier.channels()
    }
}

/** Where a tapped notification leads: MainActivity reads it from the intent, the screens follow. */
sealed interface Opening {
    data class Chat(val conversationId: Long) : Opening

    data class Diary(val id: Long) : Opening
    data class Letter(val id: Long) : Opening
}

/** Hand-made dependency wiring; the app is small enough not to need a DI framework. */
class AppContainer(private val context: Context) {
    /**
     * Outlives any screen: saves and replies started here finish even if the user leaves.
     *
     * Nothing out here may take the app down with it. Without a handler of its own, an exception
     * from any of these jobs goes to the thread's own and closes the app — which is how the rarest
     * thing in this file became the loudest: a wake that outlived its TA failed on the row it was
     * writing and killed the process. A background job that fails now is written down instead
     * (CrashLog.note, shown in 关于), and only that job is let go of.
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> CrashLog.note(context, error) },
    )

    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "cleos.db").build()
    val settings = SettingsRepository(context)
    val secrets = SecretStore(context)
    val modelProfiles = com.cleo.cleos.data.ModelProfiles(secrets)
    val images = ImageStore(context)
    /** 视频：存原片，配了「视频理解」的模型就真的发过去（见 [VideoStore]）。 */
    val videos = com.cleo.cleos.data.VideoStore(context)
    /** Files sent in the chat, kept as they came; the text read out of them sits beside them. */
    val files = FileStore(context)

    /**
     * 一个 TA 自己的工作区：**跟着角色走**。切到另一个 TA 就是另一个文件夹，
     * 它写的笔记别的 TA 读不到、也覆盖不到。
     */
    fun workspaceFor(companionId: Long): Workspace = Workspace(context, companionId)

    /**
     * 一条视频抽几帧，当成图片用（见 [VideoFrames]）。
     *
     * 放在这里而不是让调用方自己 new 一个 MediaMetadataRetriever：那玩意儿要 Context，
     * 而 ViewModel 里不该握着 Activity 的 Context。
     */
    suspend fun videoFrames(uri: android.net.Uri, count: Int = VideoFrames.DEFAULT_COUNT) =
        withContext(Dispatchers.IO) { VideoFrames.frames(context, uri, count) }

    /** 视频多长，用来在聊天里显示 `1:23`；读不出来是 0。 */
    suspend fun videoLength(uri: android.net.Uri) =
        withContext(Dispatchers.IO) { VideoFrames.durationMs(context, uri) }

    val feed = com.cleo.cleos.data.FeedRepository(db, images)

    /** 把"模型设置存在 TA 身上"搬成"独立配置 + TA 只指向它"（见 [ModelConfigs]）。 */
    val modelConfigs = com.cleo.cleos.data.ModelConfigs(db, modelProfiles)

    init {
        // 老版本的工作区是大家共用一个文件夹（filesDir/workspace 底下直接放文件）。
        // 现在按 TA 分开，把那些直接躺在那儿的归给第一个 TA —— 只有一个 TA 时就是他的。
        appScope.launch {
            val root = Workspace.root(context)
            val strays = runCatching { root.listFiles()?.filter { it.isFile }.orEmpty() }.getOrDefault(emptyList())
            if (strays.isEmpty()) return@launch
            val owner = runCatching { db.companions().all().firstOrNull()?.id }.getOrNull() ?: return@launch
            val into = File(root, owner.toString()).apply { mkdirs() }
            strays.forEach { runCatching { it.renameTo(File(into, it.name)) } }
        }
        // 模型配置搬家：跑一次就够（三个 id 字段有值就跳过）。
        // **外面套 runCatching**：这一步失败了，那几个 TA 还是老样子能用 ——
        // 旧字段这一版都留着，没必要因为它一个把整个 App 拖垮。
        appScope.launch { runCatching { modelConfigs.ensure() } }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Reasoning models can think for a long time before the first visible token.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    internal val updates = Updates(context, http, version)

    val companions = Companions(db, settings, secrets, images)
    /** Reading a page off the web, for read_webpage. */
    val web: com.cleo.cleos.ai.WebReader = com.cleo.cleos.ai.WebPages(http)

    /** Searching the web, for web_search: whichever service the person set up in settings. */
    val searcher: com.cleo.cleos.ai.WebSearcher = com.cleo.cleos.ai.WebSearch(http, secrets)
    val toolDiagnostics = com.cleo.cleos.diagnostics.ToolDiagnostics(context)
    val chatClient = ChatClient(http, diagnostic = { toolDiagnostics.record(it) })
    val feedNews = com.cleo.cleos.ai.FeedNews(http.newBuilder().callTimeout(18, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build())
    val photoSource = com.cleo.cleos.ai.PhotoLibraries(
        com.cleo.cleos.ai.OpenversePhotos(http, version) { toolDiagnostics.record(it) },
        com.cleo.cleos.ai.WordPressPhotos(http, version) { toolDiagnostics.record(it) },
    ) { settings.current().photoLibrary }
    val feedAi = com.cleo.cleos.ai.FeedAi(db, settings, secrets, chatClient, feedNews, feed, images, photoSource)
    private val calendar = PhoneCalendar(context)

    /** What the phone is playing, and its words: the chat's 一起听 bar, a reply's line about it, music_control. */
    val music = PhoneMusic(context)
    val lyrics = Lyrics(http, "Cleos/$version (${Releases.url(Releases.HOSTS.first())})", appScope)
    // Typed: its note_for_later reaches [later], which is made further down from what uses this.
    val tools: ToolBox = ToolBox(
        db.todos(),
        db.diary(),
        OpenMeteo(http),
        requests = { id -> db.messages().requestsBy(id).mapNotNull { SecretRequests.decode(it.content) } },
        avatar = AiSelfAvatar(db, images, companions),
        letters = { id -> db.letters().allFor(id) },
        memories = db.memories(),
        lore = db.lore(),
        location = PhoneLocation(context, http),
        later = { later },
        alarms = PhoneClock(context) { visible },
        calendar = calendar,
        music = music,
        patBack = { id, suffix -> chat.patBack(id, suffix) },
        reactBack = { conversation, message, emoji, remove -> chat.reactBack(conversation, message, emoji, remove) },
        planVisit = { conversation, minutes -> freeTopics.propose(conversation, minutes) },
        feed = com.cleo.cleos.ai.FeedBook(db.feed()) { id -> db.companions().get(id)?.name },
        feedActions = com.cleo.cleos.ai.FeedActions(db.feed(), { id -> db.companions().get(id) != null },
            { action -> db.withTransaction { action() } }),
        photos = com.cleo.cleos.ai.PhotoBook(photoSource, diagnostic = { toolDiagnostics.record(it) }),
        // Read at the call: the chat that answers read_file is made just below.
        fileShelf = { chat },
        // A deletion waits on a card in the chat, which is made just below as well.
        allow = { conversationId, request -> chat.allow(conversationId, request) },
        web = web,
        searcher = searcher,
        workspace = ::workspaceFor,
    )
    val recaps = Recaps(db, settings, secrets, chatClient, appScope)
    val mcp = McpHub(
        McpServers(secrets),
        McpClient(http, version),
        diagnostic = { toolDiagnostics.record(it) },
    )
    val transcriber = Transcriber(http, secrets)
    val speaker = Speaker(images, http, secrets)

    /** The TA's voice messages beside the ear, on headphones. */
    val ear = EarVoice(context, images)
    val wakeActivities = com.cleo.cleos.ai.WakeActivities(db)
    val chat: ChatRepository = ChatRepository(
        db, settings, secrets, chatClient, tools, images, videos, files, companions, recaps, mcp, transcriber, speaker, appScope,
        // 没配「视频理解」模型时的退路：从视频里抽几帧顶上（见 ChatRepository.prepareVideo）。
        videoFrames = { path, count -> com.cleo.cleos.data.VideoFrames.frames(path, count) },
        // A reply finished where the person isn't looking (they left, or went to another page): as a notification.
        replied = { ta, conversationId, said ->
            if (!(visible && chatOnScreen == conversationId)) notifier.messages(ta, conversationId, said)
            followUps.plan(ta, conversationId, said)
            freeTopics.replied(conversationId)
        },
        interrupted = { followUps.cancel(it); freeTopics.interrupt(it); feedVisits.interrupt(it) },
        activities = wakeActivities,
        listening = { Listening(music, lyrics).line() },
    )
    val stickers = Stickers(context, db, images)
    val groupChat = com.cleo.cleos.ai.GroupChatRepository(db, settings, secrets, chatClient, images, appScope,
        groupTools = com.cleo.cleos.ai.GroupTools(tools) { settings.current() },
        diagnostic = { toolDiagnostics.record(it) }) { id ->
        db.conversations().idsFor(id).any { chat.busy(it) || chat.isTyping(it) }
    }
    val favorites = com.cleo.cleos.data.Favorites(db, settings, images)
    val backup = BackupService(context, db, settings, images, files)
    val imports = ForeignImport(context, db, companions)
    val notifier = Notifier(context, images)

    /** Phone calls with a TA. */
    val calls = Calls(context, db, chat, companions, settings, transcriber, speaker, appScope)

    /** Whether the app is on screen (MainActivity, between onStart and onStop). */
    @Volatile
    var visible = false

    /**
     * The conversation whose chat is on screen right now (the chat tab showing, the app in front),
     * else null. Only what arrives there goes without a notification: the app being open is not
     * enough, since on another tab, or in settings, nothing new in the chat is seen.
     */
    @Volatile
    var chatOnScreen: Long? = null

    val later: Later = Later(
        context,
        db,
        chat,
        notifier,
        appScope,
        showing = { id -> visible && chatOnScreen == id },
        companions = companions,
        settings = settings,
        glance = Glance(db, calendar, settings) { PhoneCalendar.allowed(context) },
        activities = wakeActivities,
        inUse = {
            context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
        },
    )
    val followUps = com.cleo.cleos.ai.FollowUps(context, db, chat, notifier, appScope, wakeActivities) { id -> visible && chatOnScreen == id }
    val freeTopics = com.cleo.cleos.ai.FreeTopics(context, db, chat, notifier, appScope,
        activities = wakeActivities,
        showing = { id -> visible && chatOnScreen == id },
        onEnabled = { later.wantsNotifications.value = true })
    val letters = Letters(db, settings, secrets, chatClient, appScope, written = { later.letterWritten(it) })
    val feedVisits = com.cleo.cleos.ai.FeedVisits(context, db, feedAi, chat, secrets, appScope, wakeActivities, groupChat::busyMember)

    /** Where a tapped notification leads, until a screen has gone there. */
    val opening = MutableStateFlow<Opening?>(null)

    /** A notification about [conversationId] was tapped: its TA and the conversation become current, then the chat shows. */
    fun openConversation(conversationId: Long) {
        appScope.launch {
            val conversation = db.conversations().get(conversationId) ?: return@launch
            settings.setCurrentCompanion(conversation.companionId)
            settings.setCurrentConversation(conversationId)
            opening.value = Opening.Chat(conversationId)
        }
    }

    fun openLetter(id: Long) {
        opening.value = Opening.Letter(id)
    }

    init {
        // The first TA is made from the old settings before anything asks who is being talked to.
        appScope.launch {
            wakeActivities.recover()
            companions.ensure()
            favorites.prune()
            // What a TA brought from another app used to sit in their persona; it moves into their memory, once.
            PersonaMemory.migrate(db, System.currentTimeMillis())
            // A call the app was stopped in the middle of ended there.
            chat.closeOpenCalls()
        }
        // Notes still waiting and letters on their way get their background work back, if it was lost.
        later.reconcile()
        followUps.restore()
        freeTopics.restore()
        feedVisits.restore()
        // A reply under way is what keeps the app running in the background (ReplyKeeper), and this
        // is where that is decided: it goes up the moment one starts — the person is in front then,
        // and the system lets a service start — rather than when they leave, which is the moment
        // these phones freeze the process and the start never lands. It takes itself down again
        // when nothing is being written (ReplyKeeper).
        appScope.launch {
            kotlinx.coroutines.flow.combine(chat.working, groupChat.working) { private, groups -> private.isNotEmpty() || groups.isNotEmpty() }
                .collect { if (it) ReplyKeeper.start(context) }
        }
    }
}
