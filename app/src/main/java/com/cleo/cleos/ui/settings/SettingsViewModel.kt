package com.cleo.cleos.ui.settings

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.ChatException
import com.cleo.cleos.ai.Habits
import com.cleo.cleos.ai.SearchService
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.ai.WebException
import android.media.MediaPlayer
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.ai.SpeechException
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.ai.VoiceOption
import com.cleo.cleos.ai.VoiceService
import kotlinx.coroutines.CancellationException
import java.io.File
import com.cleo.cleos.ai.VoicePreset
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.McpServer
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.data.ImportException
import com.cleo.cleos.data.Pats
import com.cleo.cleos.data.needs
import com.cleo.cleos.data.WorkspaceEntry
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.LaterEntity
import com.cleo.cleos.data.db.LoreEntity
import com.cleo.cleos.data.db.WakeEntity
import com.cleo.cleos.ui.wallpaper.WallpaperAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PERSONA_LIMIT = Companions.PERSONA_LIMIT

/**
 * 手动粘贴的型号：**每行一个**，去空行、去重，保持原顺序。
 *
 * 抽成纯函数是为了能单独测 —— 这一处写错，人粘十行只会加进去一行。
 */
internal fun parseModelIds(text: String): List<String> =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

/**
 * 这次真正要新增的型号：已有的**不覆盖**（按「服务商 + 型号」去重），保持原顺序。
 *
 * 界面那边拿它算「已添加」标记和批量保存的清单，两处用同一套判断。
 */
internal fun modelsToAdd(existing: Set<String>, wanted: List<String>): List<String> =
    wanted.map { it.trim() }.filter { it.isNotEmpty() }.distinct().filterNot { it in existing }

/**
 * Text fields are edited locally and written back after a short pause (and once more
 * on leaving), so typing does not hit the disk on every keystroke. The API key is the
 * exception: it is only ever written, encrypted, and never read back into a field.
 *
 * The model and the TA's name belong to the TA that was current when the screen opened (or
 * the one just imported); the rest is the person's and the app's. The persona is shown here
 * but written on its own page (PersonaScreen), which saves it.
 */
/** 打开看的时候一次读这么多字：工作区里的文件本来就小。 */
private const val WORKSPACE_VIEW = 20000

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    var companionId by mutableLongStateOf(0L)
        private set
    var companionCount by mutableIntStateOf(1)
        private set
    private var deleted = false

    /** The TA's model, as edited. */
    val chat = EndpointFields(c, viewModelScope)

    /** The model the TA has for words that are heard (CompanionEntity.spokenModelOn), and whether it is on. */
    val spoken = EndpointFields(c, viewModelScope, spoken = true)
    var spokenOn by mutableStateOf(false)
    var aiName by mutableStateOf("")
    var userName by mutableStateOf("")

    /** The TA's persona as saved, kept up to date: PersonaScreen writes it, never this. */
    var persona by mutableStateOf("")
        private set
    var replyWaitSeconds by mutableStateOf(3)
    var followUpEnabled by mutableStateOf(false)
    var followUpDelaySeconds by mutableStateOf(60)
    var freeTopicEnabled by mutableStateOf(false)
    var freeTopicLevel by mutableStateOf(1)
    var freeTopicQuietOn by mutableStateOf(true)
    var freeTopicQuietStart by mutableStateOf(1380)
    var freeTopicQuietEnd by mutableStateOf(480)
        private set
    var deepThinking by mutableStateOf(false)
        private set
    var proactive by mutableStateOf(true)
        private set

    /** The person's usual day as the greetings read it (ai/Routine.kt); null until worked out. */
    var habits by mutableStateOf<Habits?>(null)
        private set
    var historySize by mutableIntStateOf(40)
    var weatherCity by mutableStateOf("")
    var voiceBaseUrl by mutableStateOf("")
    var voiceModel by mutableStateOf("")
    var voiceKeyInput by mutableStateOf("")
    var voiceTesting by mutableStateOf(false)
        private set
    var voiceResult by mutableStateOf<String?>(null)
        private set
    /** Which search service web_search goes to (a SearchService name); empty until one is picked. */
    var searchService by mutableStateOf("")
    var searchKeyInput by mutableStateOf("")
    var searchTesting by mutableStateOf(false)
        private set
    var searchResult by mutableStateOf<String?>(null)
        private set
    var photoTesting by mutableStateOf(false)
        private set
    var photoResult by mutableStateOf<String?>(null)
        private set
    var photoSelectionSaving by mutableStateOf(false)
        private set

    fun setPhotoLibrary(library: com.cleo.cleos.data.PhotoLibrary) {
        if (photoTesting || photoSelectionSaving) return
        photoSelectionSaving = true
        photoResult = null
        viewModelScope.launch {
            try { c.settings.update { it.copy(photoLibrary = library) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { photoResult = "图库选择未保存，请重试。"
            } finally { photoSelectionSaving = false }
        }
    }

    fun testPhotos() {
        if (photoTesting || photoSelectionSaving) return
        photoTesting = true
        photoResult = null
        viewModelScope.launch {
            try {
                val source = c.photoSource.forLibrary(c.settings.current().photoLibrary)
                photoResult = com.cleo.cleos.ai.PhotoCheck.run(source, progress = { photoResult = it }) { photo ->
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val stored = c.images.import(photo.bytes, photo.mime, com.cleo.cleos.ai.McpImages.MAX_EDGE)
                        try { stored.width to stored.height } finally { c.images.delete(listOf(stored.file)) }
                    }
                }
                c.toolDiagnostics.record("event=photo_test_ok")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                c.toolDiagnostics.record("event=photo_test_failed failed=true reason=${(e.cause ?: e).javaClass.simpleName}")
                val failure = e as? com.cleo.cleos.ai.ToolFailure ?: com.cleo.cleos.ai.PhotoFailures.of(e)
                photoResult = "测试未通过：${failure.note}"
            } finally { photoTesting = false }
        }
    }
    /** The service the TA's voice comes from; null while none is picked. */
    var voiceService by mutableStateOf<VoiceService?>(null)
        private set

    /** The voice picked on each service with a list, by VoiceService key. */
    val speechVoices = mutableStateMapOf<String, String>()
    var minimaxGlobal by mutableStateOf(false)
    var speechBaseUrl by mutableStateOf("")
    var speechModel by mutableStateOf("")
    var speechVoice by mutableStateOf("")
    var elevenVoice by mutableStateOf("")
    var elevenModel by mutableStateOf("")
    var speechKeyInput by mutableStateOf("")

    /** Every voice the picked service has for this account, while the list of them is open. */
    var listedVoices by mutableStateOf<List<VoiceOption>?>(null)
        private set
    var listingVoices by mutableStateOf(false)
        private set
    var listProblem by mutableStateOf<String?>(null)
        private set
    var speechBusy by mutableStateOf(false)
        private set
    var speechResult by mutableStateOf<String?>(null)
        private set
    private var speechPlayer: MediaPlayer? = null
    var loaded by mutableStateOf(false)
        private set

    var wallpaperBusy by mutableStateOf(false)
        private set
    var wallpaperError by mutableStateOf<String?>(null)
        private set

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** What came of this TA's last note coming due (ai/Later.kt), for the line under its switch. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lastWake: StateFlow<WakeEntity?> = snapshotFlow { companionId }
        .flatMapLatest { c.db.wakes().observeLatest(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val wakeActivity: StateFlow<List<com.cleo.cleos.data.db.WakeActivityEntity>> = snapshotFlow { companionId }
        .flatMapLatest { c.db.wakeActivities().observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What this TA has noted and is waiting on: how many, and when the first comes due. Not what. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val waiting: StateFlow<List<LaterEntity>> = snapshotFlow { companionId }
        .flatMapLatest { c.db.later().observeFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** This TA's 设定, for what the list of groups says; the 设定 page reads them itself. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lore: StateFlow<List<LoreEntity>> = snapshotFlow { companionId }
        .flatMapLatest { c.db.lore().observeFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The address the voice service's key is filed under (Speech.keyAddress), as the fields stand. */
    private fun speechKeyAddress(): String {
        val service = voiceService ?: return ""
        return Speech.keyAddress(AppSettings(minimaxGlobal = minimaxGlobal, speechBaseUrl = speechBaseUrl), service)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val hasSpeechKey: StateFlow<Boolean> = snapshotFlow { speechKeyAddress() }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the transcription service's address has a key yet: filed by address, like the chat keys. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasVoiceKey: StateFlow<Boolean> = snapshotFlow { voiceBaseUrl }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the chosen search service has a key yet: each service's key is filed by its endpoint. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasSearchKey: StateFlow<Boolean> = snapshotFlow { searchService }
        .flatMapLatest { name -> c.secrets.hasKey(SearchService.of(name)?.endpoint.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val mcpServers: StateFlow<List<McpServer>> = c.mcp.servers.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every TA: the one being edited is shown with its picture, and another can be picked. */
    val companions: StateFlow<List<CompanionEntity>> = c.companions.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            c.companions.all.collect { all ->
                companionCount = all.size
                all.firstOrNull { it.id == companionId }?.let { persona = it.persona }
            }
        }
        viewModelScope.launch { habits = c.later.habits() }
        // 列表上那行「里面 N 个文件」要在进页面之前就是对的。
        refreshWorkspace()
        viewModelScope.launch {
            val s = c.settings.current()
            load(c.companions.current())
            userName = s.userName
            historySize = s.historySize
            weatherCity = s.weatherCity
            voiceBaseUrl = s.voiceBaseUrl
            searchService = s.searchService
            voiceModel = s.voiceModel
            voiceService = VoiceService.of(s.speechEngine)
            speechVoices.putAll(s.speechVoices)
            minimaxGlobal = s.minimaxGlobal
            speechBaseUrl = s.speechBaseUrl
            speechModel = s.speechModel
            speechVoice = s.speechVoice
            elevenVoice = s.elevenVoice
            elevenModel = s.elevenModel
            loaded = true
            watch()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watch() {
        snapshotFlow {
            listOf(
                spokenOn,
                aiName, userName, historySize, weatherCity, voiceBaseUrl, voiceModel,
                voiceService, speechVoices.toMap(), minimaxGlobal, speechBaseUrl, speechModel, speechVoice, elevenVoice, elevenModel,
            )
        }
            .drop(1)
            .debounce(500)
            .collect { persist() }
    }

    /** Which TA the fields edit. Everything is set in one go, with nothing in between. */
    private fun load(ta: CompanionEntity) {
        companionId = ta.id
        chat.load(ta.apiBaseUrl, ta.apiModel, ta.id)
        spokenOn = ta.spokenModelOn
        spoken.load(ta.spokenApiBaseUrl, ta.spokenApiModel, ta.id)
        featureModels = com.cleo.cleos.data.db.FeatureModels.decode(ta.featureModels)
        aiName = ta.name
        persona = ta.persona
        replyWaitSeconds = com.cleo.cleos.ai.ReplyWaitRules.seconds(ta.replyWaitSeconds)
        followUpEnabled = ta.followUpEnabled
        followUpDelaySeconds = com.cleo.cleos.ai.FollowUpRules.seconds(ta.followUpDelaySeconds)
        freeTopicEnabled = ta.freeTopicEnabled
        freeTopicLevel = com.cleo.cleos.ai.FreeTopicRules.level(ta.freeTopicLevel).id
        freeTopicQuietOn = ta.freeTopicQuietOn
        freeTopicQuietStart = com.cleo.cleos.ai.FreeTopicRules.minute(ta.freeTopicQuietStart, 1380)
        freeTopicQuietEnd = com.cleo.cleos.ai.FreeTopicRules.minute(ta.freeTopicQuietEnd, 480)
        deepThinking = ta.deepThinking
        proactive = ta.proactive
    }

    private suspend fun persist() {
        if (!loaded || deleted) return
        // Read before suspending: the fields can be loaded with another TA meanwhile (an
        // import), and the old TA must not get the new one's values.
        val id = companionId
        val heardOn = spokenOn
        val name = aiName.trim()
        val user = userName.trim()
        val history = historySize
        val city = weatherCity.trim()
        val voiceUrl = voiceBaseUrl.trim()
        val voiceM = voiceModel.trim()
        // Not the persona: written on its own page, it must not be put back as it was when this one opened.
        c.companions.update(id) {
            it.copy(name = name, spokenModelOn = heardOn)
        }
        val speech = speechSettings()
        c.settings.update {
            speech(it.copy(userName = user, historySize = history, weatherCity = city, voiceBaseUrl = voiceUrl, voiceModel = voiceM))
        }
    }

    /**
     * The settings of another TA, who also becomes the one being talked to (as picking them on the
     * home page does). What was typed for this one is saved first, a key left unsaved included.
     */
    fun switchCompanion(id: Long) {
        if (id == companionId) return
        viewModelScope.launch {
            persist()
            c.companions.select(id)
            load(c.companions.current())
        }
    }

    /** A picture cropped on the profile page; it replaces an emoji the TA picked for itself. */
    fun setAvatar(picture: Bitmap) {
        val id = companionId
        c.appScope.launch { c.companions.setAvatar(id, c.images.save(picture, prefix = "avatar-"), emoji = null) }
    }

    /** Back to the first letter of the name. */
    fun clearAvatar() {
        val id = companionId
        c.appScope.launch { c.companions.setAvatar(id, null, emoji = null) }
    }

    fun setChatTextSize(size: Int) {
        viewModelScope.launch { c.settings.update { it.copy(chatTextSize = size) } }
    }

    fun setChatAvatars(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(chatAvatars = on) } }
    }

    fun setAvatarEachMessage(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(avatarEachMessage = on) } }
    }

    /** 拍一拍: the verb, what follows the TA's name, and the buzz — what the chat's dialog edits too. */
    fun setPat(verb: String, suffix: String, buzz: Boolean) {
        viewModelScope.launch {
            c.settings.update {
                it.copy(patVerb = Pats.cleanVerb(verb), patSuffix = Pats.cleanSuffix(suffix), patBuzz = buzz)
            }
        }
    }

    fun setPatBuzz(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(patBuzz = on) } }
    }

    /** Removes this TA with their conversations and diary; [then] leaves the screen. */
    fun deleteCompanion(then: () -> Unit) {
        deleted = true
        val id = companionId
        viewModelScope.launch {
            c.chat.stopRepliesOf(id)
            c.companions.delete(id)
            then()
        }
    }

    /** The voice fields as they are now, to lay over settings (read before anything suspends). */
    private fun speechSettings(): (AppSettings) -> AppSettings {
        val engine = voiceService?.key.orEmpty()
        val voices = speechVoices.toMap().mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val global = minimaxGlobal
        val url = speechBaseUrl.trim()
        val m = speechModel.trim()
        val voice = speechVoice.trim()
        val eVoice = elevenVoice.trim()
        val eModel = elevenModel.trim()
        return {
            it.copy(
                speechEngine = engine,
                speechVoices = voices,
                minimaxGlobal = global,
                speechBaseUrl = url,
                speechModel = m,
                speechVoice = voice,
                elevenVoice = eVoice,
                elevenModel = eModel,
            )
        }
    }

    fun pickService(service: VoiceService) {
        voiceService = service
        speechKeyInput = ""
        speechResult = null
        listProblem = null
    }

    /** The voice [service] speaks in: ElevenLabs' and the hand-filled service's are fields of their own. */
    fun voiceOf(service: VoiceService): String = when (service) {
        VoiceService.ElevenLabs -> elevenVoice
        VoiceService.Other -> speechVoice
        else -> speechVoices[service.key] ?: Speech.builtIn(service).first().id
    }

    fun pickVoice(service: VoiceService, id: String) {
        when (service) {
            VoiceService.ElevenLabs -> elevenVoice = id
            VoiceService.Other -> speechVoice = id
            else -> speechVoices[service.key] = id
        }
        speechResult = null
    }

    fun setMinimaxSite(global: Boolean) {
        minimaxGlobal = global
        speechKeyInput = ""
        speechResult = null
    }

    /** Asks the service for every voice it has for this account, and opens the list of them. */
    fun listVoices() {
        val service = voiceService ?: return
        if (listingVoices) return
        listingVoices = true
        listProblem = null
        val s = speechSettings()(settings.value)
        viewModelScope.launch {
            try {
                val found = c.speaker.voices(s, service)
                if (found.isEmpty()) listProblem = "没列出音色来" else listedVoices = found
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                listProblem = e.message
            } finally {
                listingVoices = false
            }
        }
    }

    fun closeVoiceList() {
        listedVoices = null
    }

    fun saveSpeechKey() {
        val url = speechKeyAddress().trim()
        val key = speechKeyInput.trim()
        if (url.isEmpty() || key.isEmpty()) return
        viewModelScope.launch {
            c.secrets.setKey(url, key)
            speechKeyInput = ""
        }
    }

    /** Says a sentence with the voice as set up on screen, saved or not, and plays it. */
    fun previewSpeech() {
        if (speechBusy) return
        speechBusy = true
        speechResult = null
        val s = speechSettings()(settings.value)
        viewModelScope.launch {
            speechResult = try {
                val clip = c.speaker.speak(s, Speech.SAMPLE)
                // Heard the way the TA's messages will be: beside the ear, on headphones.
                val ear = if (settings.value.earVoice && c.ear.headphones()) c.ear.prepared(clip.file) else null
                play(c.images.file(clip.file), ear)
                "能用，正在放（${Voice.duration(clip.ms)}${if (ear != null) "，戴着耳机，在耳边" else ""}）。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "没成：${e.message ?: e.javaClass.simpleName}"
            }
            speechBusy = false
        }
    }

    /** Plays a try-out ([ear], what was made of it for the ear, when there is that), then throws both away. */
    private fun play(file: File, ear: File? = null) {
        speechPlayer?.release()
        val p = MediaPlayer()
        fun gone() {
            file.delete()
            if (ear != null) c.ear.forget(file.name)
        }
        runCatching {
            p.setDataSource((ear ?: file).path)
            p.setOnCompletionListener {
                it.release()
                if (speechPlayer === it) speechPlayer = null
                gone()
            }
            p.prepare()
            p.start()
            speechPlayer = p
        }.onFailure {
            p.release()
            gone()
            throw it
        }
    }

    fun applyVoicePreset(p: VoicePreset) {
        voiceBaseUrl = p.baseUrl
        voiceModel = p.model
        voiceResult = null
    }

    fun saveVoiceKey() {
        val url = voiceBaseUrl.trim()
        val key = voiceKeyInput.trim()
        if (url.isEmpty() || key.isEmpty()) return
        viewModelScope.launch {
            c.secrets.setKey(url, key)
            voiceKeyInput = ""
        }
    }

    /** Sends a second of silence: a service that answers at all is set up right. */
    fun testVoice() {
        if (voiceTesting || voiceBaseUrl.isBlank()) return
        voiceTesting = true
        voiceResult = null
        viewModelScope.launch {
            val probe = c.images.file("voice_probe.wav")
            voiceResult = try {
                withContext(Dispatchers.IO) { probe.writeBytes(Voice.silence()) }
                c.transcriber.transcribe(voiceBaseUrl, voiceModel, probe)
                "能用。（试的是一秒静音，所以没转出字来。）"
            } catch (e: ChatException) {
                "没成：${e.message}"
            } finally {
                withContext(Dispatchers.IO) { probe.delete() }
            }
            voiceTesting = false
        }
    }

    /** Pick a search service. Keys are filed per service, so switching between them keeps both. */
    fun chooseSearch(service: SearchService) {
        searchService = service.name
        searchResult = null
        viewModelScope.launch { c.settings.update { it.copy(searchService = service.name) } }
    }

    fun saveSearchKey() {
        val key = searchKeyInput.trim()
        val endpoint = SearchService.of(searchService)?.endpoint ?: return
        if (key.isEmpty()) return
        viewModelScope.launch {
            c.secrets.setKey(endpoint, key)
            searchKeyInput = ""
            searchResult = null
        }
    }

    /** A throwaway search: if results come back, the service and the key are both right. */
    fun testSearch() {
        if (searchTesting || SearchService.of(searchService) == null) return
        searchTesting = true
        searchResult = null
        viewModelScope.launch {
            searchResult = try {
                val hits = c.searcher.search(c.settings.current(), "今天的天气", 3)
                if (hits.isEmpty()) "连上了，但没搜到结果（换个词再试）" else "能用，拿回 ${hits.size} 条：${hits.first().title.take(30)}"
            } catch (e: WebException) {
                "没用成：${e.message}"
            } catch (e: Exception) {
                "没用成：${e.message ?: "未知错误"}"
            }
            searchTesting = false
        }
    }

    // ---- 工作区：TA 写的文件。这里只负责看、导出、删；写是 TA 的事。 ----------------------

    /** 正在看的文件夹，相对路径，空字符串是根。 */
    var workspacePath by mutableStateOf("")
    var workspaceEntries by mutableStateOf<List<WorkspaceEntry>>(emptyList())
        private set
    var workspaceUsage by mutableStateOf(0 to 0L)
        private set
    var workspaceNote by mutableStateOf<String?>(null)
        private set
    /** 正在打开的那个文件：路径 to 内容。 */
    var workspaceOpen by mutableStateOf<Pair<String, String>?>(null)
        private set

    /** 进到一个文件夹（空字符串是根）。 */
    fun openWorkspace(path: String) {
        workspacePath = path
        workspaceNote = null
        refreshWorkspace()
    }

    fun refreshWorkspace() {
        val id = companionId
        viewModelScope.launch {
            val (entries, usage) = withContext(Dispatchers.IO) {
                runCatching { c.workspaceFor(id).list(workspacePath) to c.workspaceFor(id).usage() }
                    .getOrElse { emptyList<WorkspaceEntry>() to (0 to 0L) }
            }
            workspaceEntries = entries
            workspaceUsage = usage
        }
    }

    fun openWorkspaceFile(path: String) {
        val id = companionId
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { c.workspaceFor(id).read(path, 0, WORKSPACE_VIEW) }
                    .onFailure { workspaceNote = it.message }
                    .getOrDefault("")
            }
            workspaceOpen = path to text
        }
    }

    fun closeWorkspaceFile() {
        workspaceOpen = null
    }

    /** 导出时要的那段文字；调用的人自己在 IO 线程上拿。 */
    fun workspaceText(path: String): String = c.workspaceFor(companionId).read(path, 0, WORKSPACE_VIEW)

    fun deleteWorkspace(path: String) {
        val id = companionId
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { c.workspaceFor(id).delete(path); null }.getOrElse { it.message ?: "没能删掉" }
            }
            workspaceNote = message ?: "删掉了「$path」"
            refreshWorkspace()
        }
    }

    fun noteWorkspace(text: String) {
        workspaceNote = text
    }

    fun setMcpEnabled(id: String, on: Boolean) {
        viewModelScope.launch { c.mcp.servers.setEnabled(id, on) }
    }

    /** Takes effect at once, like the tool switches. */
    fun setReplyWait(seconds: Int) {
        val value = com.cleo.cleos.ai.ReplyWaitRules.seconds(seconds)
        replyWaitSeconds = value
        val id = companionId
        viewModelScope.launch { c.companions.update(id) { it.copy(replyWaitSeconds = value) } }
    }

    fun setFollowUp(on: Boolean) {
        followUpEnabled = on
        val id = companionId
        viewModelScope.launch {
            c.companions.update(id) { it.copy(followUpEnabled = on) }
            if (!on) c.followUps.cancelFor(id)
        }
    }

    fun setFollowUpDelay(seconds: Int) {
        val value = com.cleo.cleos.ai.FollowUpRules.seconds(seconds)
        followUpDelaySeconds = value
        val id = companionId
        viewModelScope.launch { c.companions.update(id) { it.copy(followUpDelaySeconds = value) } }
    }

    fun setFreeTopic(on: Boolean) {
        freeTopicEnabled = on
        changeFreeTopic { it.copy(freeTopicEnabled = on) }
    }

    fun chooseFreeTopicLevel(level: Int) {
        freeTopicLevel = com.cleo.cleos.ai.FreeTopicRules.level(level).id
        val value = freeTopicLevel
        changeFreeTopic { it.copy(freeTopicLevel = value) }
    }

    fun setFreeTopicQuiet(on: Boolean) {
        freeTopicQuietOn = on
        changeFreeTopic { it.copy(freeTopicQuietOn = on) }
    }

    fun setFreeTopicTime(start: Boolean, minutes: Int) {
        val value = com.cleo.cleos.ai.FreeTopicRules.minute(minutes, if (start) 1380 else 480)
        if (start) freeTopicQuietStart = value else freeTopicQuietEnd = value
        changeFreeTopic { if (start) it.copy(freeTopicQuietStart = value) else it.copy(freeTopicQuietEnd = value) }
    }

    private fun changeFreeTopic(transform: (com.cleo.cleos.data.db.CompanionEntity) -> com.cleo.cleos.data.db.CompanionEntity) {
        val id = companionId
        viewModelScope.launch {
            c.companions.update(id, transform)
            c.freeTopics.configure(id)
        }
    }

    fun setThinking(on: Boolean) {
        deepThinking = on
        val id = companionId
        viewModelScope.launch { c.companions.update(id) { it.copy(deepThinking = on) } }
    }

    /** Off, what the TA noted goes unsaid: each note, when it comes due, finds the switch off and is dropped. */
    fun setReachOut(on: Boolean) {
        proactive = on
        val id = companionId
        viewModelScope.launch { c.companions.update(id) { it.copy(proactive = on) } }
    }

    /** Whether notifications can reach the person at all (the app's switch, and on Android 13 the permission). */
    fun notificationsAllowed(): Boolean = c.notifier.allowed()

    /** The permission is being asked for here: the app asks only once (see MainActivity). */
    fun markNotificationsAsked() {
        viewModelScope.launch { c.settings.update { it.copy(notificationsAsked = true) } }
    }

    fun setTool(group: ToolGroup, on: Boolean) {
        viewModelScope.launch {
            c.settings.update { it.copy(tools = if (on) it.tools + group else it.tools - group) }
        }
    }

    /**
     * 每个功能各自配的模型（见 [Feature]）。空 map = 全都跟着主模型走。
     *
     * 这是界面上那份的**副本**：改完立刻更新它（所以界面马上有反应），
     * 再落库（[setFeatureModel]）。两边都做，是因为落库要走协程，
     * 只靠它的话按钮点下去会有一会儿没动静。
     */
    var featureModels by mutableStateOf<Map<com.cleo.cleos.data.db.Feature, com.cleo.cleos.data.db.FeatureModel>>(emptyMap())
        private set

    /** 某个功能单独配的模型；null 表示它跟着主模型。 */
    fun featureModel(feature: com.cleo.cleos.data.db.Feature) = featureModels[feature]

    /** 选模型时给出的一项：用谁家的、哪个型号。 */
    typealias ModelChoice = com.cleo.cleos.data.ModelChoices.Choice

    /**
     * 选模型时能选的东西 —— **从实际在用的出发**（主模型 → 语音 → 保存过的配置）。
     *
     * 逻辑在 `ModelChoices.of` 里，聊天页那个"点模型名切换"用的是同一份，
     * 免得两处各列一套、越走越不一样。
     */
    fun modelChoices(): List<ModelChoice> = com.cleo.cleos.data.ModelChoices.of(
        chatBaseUrl = chat.activeUrl,
        chatModel = chat.activeModel,
        spokenOn = spokenOn,
        spokenBaseUrl = spoken.activeUrl,
        spokenModel = spoken.activeModel,
        profiles = modelProfiles.value,
        nameOf = { url -> com.cleo.cleos.data.ApiPresets.at(url)?.name },
    )

    /**
     * 这个功能配的模型，缺哪些它需要的能力。
     *
     * 一边是**功能要什么**（`Feature.needs`），一边是**配置声明有什么**（`ModelProfile.direct*`），
     * 两边对不上就在这里返回 —— 界面拿它画红字。
     *
     * 没单独配（跟着主模型走）时，就检查主模型那条配置。
     */
    fun missingFor(
        feature: com.cleo.cleos.data.db.Feature,
        picked: com.cleo.cleos.data.db.FeatureModel?,
    ): Set<com.cleo.cleos.data.Capability> {
        if (feature.needs.isEmpty()) return emptySet()
        val profile = profileFor(feature, picked)
        // 找不到那条配置（比如手填的地址）→ 当作"没声明过"，也提醒一句。
        if (profile == null) return feature.needs
        return com.cleo.cleos.data.missingCapabilities(feature, profile)
    }

    /** 这个功能实际用的是哪条配置。 */
    private fun profileFor(
        feature: com.cleo.cleos.data.db.Feature,
        picked: com.cleo.cleos.data.db.FeatureModel?,
    ): com.cleo.cleos.data.ModelProfile? {
        val url = picked?.baseUrl?.trim().orEmpty().ifEmpty { chat.activeUrl.trim() }
        val model = picked?.model?.trim().orEmpty().ifEmpty { chat.activeModel.trim() }
        return modelProfiles.value.firstOrNull {
            it.baseUrl.trim() == url && it.model.trim() == model && !it.forgotten
        }
    }

    /** 正在测哪个功能；null 表示没在测。 */
    var probing by mutableStateOf<com.cleo.cleos.data.db.Feature?>(null)
        private set

    /** 上一次测试的结果，带着是哪个功能测的；显示一下就消失。 */
    var probeResult by mutableStateOf<Pair<com.cleo.cleos.data.db.Feature, com.cleo.cleos.ai.ProbeResult>?>(null)
        private set

    fun clearProbeResult() { probeResult = null }

    /**
     * 测这个功能配的模型**能不能干这个活**。
     *
     * 照 Operit 的做法：**每个功能测它真实要干的那件事**，不是发一句 "Hi" 就算通。
     * 图像识别就画一张印着随机码的图让它认 —— **码不在提示词里，蒙不了**；
     * 认出来了才把 [com.cleo.cleos.data.Capability.Image] 记到那条配置上。
     *
     * **验过才记**，不是"我猜它会" —— 这就是之前"在图像识别配了不会看图的模型"
     * 那种事不会再发生的原因。
     */
    fun testCapability(feature: com.cleo.cleos.data.db.Feature) {
        val need = feature.needs
        if (need.isEmpty() || probing != null) return
        val profile = profileFor(feature, featureModel(feature))
        if (profile == null) {
            probeResult = feature to com.cleo.cleos.ai.ProbeResult(false, "这条配置还没保存成一条「模型配置」—— 先在「连接」里点一次「保存当前配置」，再回来测。")
            return
        }
        probing = feature
        viewModelScope.launch {
            var last = com.cleo.cleos.ai.ProbeResult(false, "没有可测的能力")
            for (capability in need) {
                last = when (capability) {
                    com.cleo.cleos.data.Capability.Image -> c.capabilityTester.testImage(profile)
                    com.cleo.cleos.data.Capability.Video -> c.capabilityTester.testVideo(profile)
                }
                if (last.passed) c.modelProfiles.setCapability(profile.id, capability, true)
            }
            probeResult = feature to last
            probing = null
        }
    }

    /**
     * 已经保存过的模型配置（主模型页那份），给"按功能换模型"当选项用。
     *
     * **不能让人手打模型名** —— `deepseek-flash`、`claude-sonnet-5[次]` 这种名字谁记得住？
     * 她配过的服务就摆在这儿，点一下选走。
     */
    val modelProfiles: kotlinx.coroutines.flow.StateFlow<List<com.cleo.cleos.data.ModelProfile>> =
        c.modelProfiles.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 给某个功能单独配模型，或者传 null 改回"跟随主模型"。
     *
     * 地址和模型名**两个都填了才算数** —— 只填一半的话，[CompanionEntity.modelFor]
     * 那边也会当没配，所以这里存之前先判掉，免得界面上显示"已配置"、实际却走了主模型。
     */
    fun setFeatureModel(feature: com.cleo.cleos.data.db.Feature, model: com.cleo.cleos.data.db.FeatureModel?) {
        val filled = model?.takeIf { it.baseUrl.isNotBlank() && it.model.isNotBlank() }
        featureModels = featureModels.toMutableMap().apply {
            if (filled == null) remove(feature) else put(feature, filled)
        }
        val id = companionId
        viewModelScope.launch {
            c.companions.update(id) { ta ->
                val now = com.cleo.cleos.data.db.FeatureModels.decode(ta.featureModels).toMutableMap()
                if (filled == null) now.remove(feature) else now[feature] = filled
                ta.copy(featureModels = com.cleo.cleos.data.db.FeatureModels.encode(now))
            }
        }
    }

    fun setLetterEveryDays(days: Int) {
        viewModelScope.launch { c.settings.update { it.copy(letterEveryDays = days) } }
    }

    var bubbleImageBusy by mutableStateOf(false)
        private set
    var bubbleImageError by mutableStateOf<String?>(null)
        private set
    fun importBubbleComponent(bitmap: android.graphics.Bitmap, target: String) {
        if (target == "face" || target == "star") { importBubbleImage(bitmap, target == "face"); return }
        if (bubbleImageBusy) return
        bubbleImageBusy = true
        bubbleImageError = null
        viewModelScope.launch {
            try {
                val file = c.images.save(bitmap, prefix = "bubble-")
                c.settings.update { settings -> settings.copy(bubbleDecoration = settings.bubbleDecoration.copy(
                    components = settings.bubbleDecoration.components.map { if (it.id == target) it.copy(image = file, emoji = "", enabled = true, shape = "original") else it })) }
            } catch (e: Exception) { bubbleImageError = "这张图读不出来，请换一张试试。" }
            finally { bubbleImageBusy = false }
        }
    }

    fun importBubbleImage(bitmap: android.graphics.Bitmap, face: Boolean) {
        if (bubbleImageBusy) return
        bubbleImageBusy = true
        bubbleImageError = null
        viewModelScope.launch {
            try {
                val file = c.images.save(bitmap, prefix = "bubble-")
                c.settings.update {
                    val d = it.bubbleDecoration
                    it.copy(bubbleDecoration = (if (face) d.copy(faceImage = file, faceEmoji = "", faceEnabled = true, faceShape = "original")
                        else d.copy(starImage = file, starEmoji = "", starsEnabled = true, starSize = 24, starShape = "original")).normalized())
                }
            } catch (e: Exception) { bubbleImageError = "这张图读不出来，请换一张试试。" }
            finally { bubbleImageBusy = false }
        }
    }

    fun setWallpaper(uri: Uri) {
        viewModelScope.launch {
            wallpaperBusy = true
            wallpaperError = null
            try {
                val old = settings.value.wallpaper
                val stored = c.images.import(uri, maxEdge = 2560, prefix = "wallpaper-")
                val tone = withContext(Dispatchers.Default) {
                    c.images.thumbnail(stored.file)?.let { WallpaperAnalyzer.analyze(it) }
                }
                c.settings.update {
                    it.copy(
                        wallpaper = stored.file,
                        wallpaperDark = tone?.dark,
                        wallpaperHue = tone?.hue,
                        wallpaperChroma = tone?.chroma,
                        wallpaperTrough = tone?.trough,
                        wallpaperPeak = tone?.peak,
                    )
                }
                if (old != null && old != stored.file) c.images.delete(listOf(old))
            } catch (e: Exception) {
                wallpaperError = "这张图读不出来：${e.message ?: e.javaClass.simpleName}"
            } finally {
                wallpaperBusy = false
            }
        }
    }

    fun resetWallpaper() {
        viewModelScope.launch {
            val old = settings.value.wallpaper
            c.settings.update {
                it.copy(
                    wallpaper = null,
                    wallpaperDark = null,
                    wallpaperHue = null,
                    wallpaperChroma = null,
                    wallpaperTrough = null,
                    wallpaperPeak = null,
                )
            }
            if (old != null) c.images.delete(listOf(old))
        }
    }

    fun setGlassMode(mode: GlassMode) {
        viewModelScope.launch { c.settings.update { it.copy(glassMode = mode) } }
    }

    /** The colour of the person's own bubbles (ARGB); null follows the wallpaper. */
    fun setMyBubble(argb: Int?) {
        viewModelScope.launch { c.settings.update { it.copy(myBubble = argb) } }
    }

    var bubblePresetBusy by mutableStateOf(false)
        private set
    var bubblePresetMessage by mutableStateOf<String?>(null)
        private set
    private var previousBubblePreset: Pair<Long, com.cleo.cleos.data.BubblePreset>? = null

    fun saveBubblePreset(name: String) {
        val id = companionId
        viewModelScope.launch {
            c.settings.update {
                if (it.bubblePresets.size >= 50) { bubblePresetMessage = "最多保存 50 套方案，请先删除不用的方案。"; it }
                else { bubblePresetMessage = "已保存方案。"; it.copy(bubblePresets = it.bubblePresets + com.cleo.cleos.data.BubblePreset.capture(it, id, name)) }
            }
        }
    }
    fun applyBubblePreset(preset: com.cleo.cleos.data.BubblePreset) {
        val id = companionId
        viewModelScope.launch { c.settings.update {
            previousBubblePreset = id to com.cleo.cleos.data.BubblePreset.capture(it, id, "切换前")
            bubblePresetMessage = "已使用「${preset.name}」，可撤回这次切换。"
            preset.apply(it, id)
        } }
    }
    fun undoBubblePreset() {
        val previous = previousBubblePreset ?: return
        viewModelScope.launch { c.settings.update { previous.second.apply(it, previous.first) } }
        previousBubblePreset = null
        bubblePresetMessage = "已恢复切换前的气泡。"
    }
    fun deleteBubblePreset(id: String) {
        viewModelScope.launch { c.settings.update { it.copy(bubblePresets = it.bubblePresets.filter { preset -> preset.id != id }) } }
    }
    fun exportBubblePreset(id: String, open: () -> java.io.OutputStream?) {
        if (bubblePresetBusy) return
        bubblePresetBusy = true; bubblePresetMessage = null
        viewModelScope.launch {
            try {
                val preset = settings.value.bubblePresets.find { it.id == id } ?: error("方案已删除。")
                withContext(Dispatchers.IO) { val stream = open() ?: error("无法创建文件。")
                    stream.use { com.cleo.cleos.data.BubblePackage(c.images).export(preset, it) } }
                bubblePresetMessage = "气泡包已导出。"
            } catch (e: Exception) { bubblePresetMessage = "导出失败：${e.message ?: "请重试"}" }
            finally { bubblePresetBusy = false }
        }
    }
    fun importBubblePreset(open: () -> java.io.InputStream?) {
        if (bubblePresetBusy) return
        if (settings.value.bubblePresets.size >= 50) { bubblePresetMessage = "最多保存 50 套方案，请先删除不用的方案。"; return }
        bubblePresetBusy = true; bubblePresetMessage = null
        viewModelScope.launch {
            try {
                val preset = withContext(Dispatchers.IO) { val stream = open() ?: error("无法打开文件。")
                    stream.use { com.cleo.cleos.data.BubblePackage(c.images).import(it) } }
                c.settings.update { it.copy(bubblePresets = it.bubblePresets + preset) }
                bubblePresetMessage = "已导入「${preset.name}」，点使用即可切换。"
            } catch (e: Exception) { bubblePresetMessage = "导入失败：${e.message ?: "文件无法读取"}" }
            finally { bubblePresetBusy = false }
        }
    }

    fun setBubbleTheme(mine: Boolean, theme: String) {
        val id = companionId.toString()
        val chosen = com.cleo.cleos.ui.chat.BubbleThemes.valid(theme)
        viewModelScope.launch {
            c.settings.update { if (mine) it.copy(myBubbleTheme = chosen)
                else it.copy(taBubbleThemes = it.taBubbleThemes + (id to chosen)) }
        }
    }

    fun setBubblePadding(horizontal: Int, vertical: Int) {
        viewModelScope.launch { c.settings.update { it.copy(bubblePaddingX = horizontal.coerceIn(6, 24), bubblePaddingY = vertical.coerceIn(4, 16)) } }
    }

    fun setMessageSpacing(value: Int) {
        viewModelScope.launch { c.settings.update { it.copy(messageSpacing = com.cleo.cleos.data.BubbleSpacing.normalized(value)) } }
    }

    fun setBubbleDecoration(value: com.cleo.cleos.data.BubbleDecoration) {
        viewModelScope.launch { c.settings.update { it.copy(bubbleDecoration = value.normalized()) } }
    }

    fun setBubbleBackground(mine: Boolean, theme: String, value: com.cleo.cleos.data.BubbleBackground?) {
        val key = com.cleo.cleos.data.BubbleBackground.key(mine, companionId, theme)
        viewModelScope.launch { c.settings.update { it.copy(bubbleBackgrounds = if (value == null) it.bubbleBackgrounds - key else it.bubbleBackgrounds + (key to value.normalized())) } }
    }

    /** The TA's voice messages beside the ear on headphones (EarVoice). */
    fun setEarVoice(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(earVoice = on) } }
    }

    fun setExpandVoiceText(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(expandVoiceText = on) } }
    }

    var backupBusy by mutableStateOf(false)
        private set
    var backupMessage by mutableStateOf<String?>(null)
        private set
    var canUndoRestore by mutableStateOf(c.backup.hasSnapshot)
        private set

    fun exportBackup(uri: Uri) = runBackup("导出了") { c.backup.export(uri) }

    // A reply still running would write into conversations that are about to be replaced.
    fun restoreBackup(uri: Uri) = runBackup("恢复了") {
        c.groupChat.stopAll()
        try {
            c.chat.stopAll(); c.feedVisits.stopAll()
            c.backup.restore(uri)
        } finally { c.groupChat.resume(); c.freeTopics.restore(); c.feedVisits.restore() }
    }

    fun undoRestore() = runBackup("撤销了，回到恢复前：") {
        c.groupChat.stopAll()
        try {
            c.chat.stopAll(); c.feedVisits.stopAll()
            c.backup.undoRestore()
        } finally { c.groupChat.resume(); c.freeTopics.restore(); c.feedVisits.restore() }
    }

    /** A memory file being read in, and the word on how it went. */
    var memoryBusy by mutableStateOf(false)
        private set
    var memoryMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Brings a file from another app in: a 角色卡 (one TA each, and everything else in the file
     * goes to the first of them), a world book, a memory store, a chat log — any of the four, or
     * several at once. Nothing already here is touched: a memory of the same name only gets the
     * details it was missing, a 设定 entry that is already there is left be, and a memory whose
     * kind is full goes to 设定 rather than nowhere.
     */
    fun importMemoryFile(uri: Uri) {
        if (memoryBusy) return
        val id = companionId
        if (id <= 0L) return
        memoryBusy = true
        memoryMessage = null
        viewModelScope.launch {
            try {
                val r = c.imports.import(uri, id)
                memoryMessage = r.said()
                // A 角色卡 makes a TA (a file of cards, one each) and switches to the first:
                // the fields here follow, so what is typed next is about the one just imported.
                r.newTaId?.let { made ->
                    persist()
                    c.companions.select(made)
                    load(c.companions.current())
                }
            } catch (e: ImportException) {
                memoryMessage = e.message
            } catch (e: Exception) {
                memoryMessage = "出错了：${e.message ?: e.javaClass.simpleName}"
            } finally {
                memoryBusy = false
            }
        }
    }

    // App scope: a restore half done because the screen was closed is the worst outcome.
    private fun runBackup(done: String, action: suspend () -> Any) {
        if (backupBusy) return
        backupBusy = true
        backupMessage = null
        c.appScope.launch {
            val message = try {
                "$done ${action()}"
            } catch (e: Exception) {
                (e as? com.cleo.cleos.data.BackupException)?.message ?: "出错了：${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) {
                backupMessage = message
                backupBusy = false
                canUndoRestore = c.backup.hasSnapshot
            }
        }
    }

    // ---- 三层结构：服务商 / 模型配置 / 功能绑定 ----

    /** 现在这份三层配置；[com.cleo.cleos.data.ModelRegistry.edit] 写完就刷新。 */
    val registry: StateFlow<com.cleo.cleos.data.ModelRegistryData> =
        c.registry.data.stateIn(viewModelScope, SharingStarted.Eagerly, com.cleo.cleos.data.ModelRegistryData())

    var registryBusy by mutableStateOf(false)
        private set
    var registryMessage by mutableStateOf<String?>(null)
        private set

    fun clearRegistryMessage() { registryMessage = null }

    /** 改配置一律走这里：按钮点下去有个忙碌状态，出错就把原话显示出来。 */
    private fun registryAction(action: suspend () -> String?) {
        if (registryBusy) return
        registryBusy = true
        viewModelScope.launch {
            try { registryMessage = action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { registryMessage = e.message ?: "没做成，再试一次" }
            finally { registryBusy = false }
        }
    }

    /** 正在测哪条（服务商的 id、或模型配置的 id）；null 表示没在测。 */
    var testing by mutableStateOf<String?>(null)
        private set

    /** 上一次连接测试的结果：id → 那句人话。 */
    var testResult by mutableStateOf<Pair<String, String>?>(null)
        private set

    /**
     * 上一次「测一下」的结果（模型配置 id → 结果）。
     *
     * **带四态**（[com.cleo.cleos.ai.ProbeResult.outcome]）和脱敏细节：
     * 网络/鉴权/格式出错**不能**算成「这个模型不会看」。
     */
    var capabilityResult by mutableStateOf<Pair<String, com.cleo.cleos.ai.ProbeResult>?>(null)
        private set

    fun clearTestResult() { testResult = null }

    // ---- 服务商 ----

    fun addProvider(name: String, baseUrl: String, kind: com.cleo.cleos.data.ApiKind, key: String) = registryAction {
        val url = baseUrl.trim()
        require(url.isNotBlank()) { "接口地址不能空着" }
        require(c.registry.current().providers.none { it.baseUrl.trim().trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true) }) {
            "这个地址已经有一条服务商了"
        }
        if (key.isNotBlank()) c.secrets.setKey(url, key.trim())
        c.registry.addProvider(name, url, kind)
        "已添加。点「测试连接」真发一条试试。"
    }

    fun updateProvider(id: String, name: String, baseUrl: String, kind: com.cleo.cleos.data.ApiKind, key: String) = registryAction {
        val url = baseUrl.trim()
        require(url.isNotBlank()) { "接口地址不能空着" }
        if (key.isNotBlank()) c.secrets.setKey(url, key.trim())
        c.registry.updateProvider(id) { it.copy(name = name.trim(), baseUrl = url, kind = kind) }
        "已保存"
    }

    /** 删服务商：名下的模型配置一起删，引用它们的功能退回全局默认（由 [com.cleo.cleos.data.ModelRegistry.removeProvider] 报出来）。 */
    fun removeProvider(id: String) = registryAction {
        val configs = registry.value.configs.count { it.providerId == id }
        val affected = c.registry.removeProvider(id)
        "已删除服务商，连同它名下 $configs 条模型配置" + if (affected.isEmpty()) "。" else "；${affected.joinToString("、") { it.label }} 回到跟随全局默认。"
    }

    /** 连接测试：**真发一条最小请求**，不是"能列模型就算通"。 */
    fun testProvider(provider: com.cleo.cleos.data.Provider) {
        if (testing != null) return
        testing = provider.id
        testResult = null
        viewModelScope.launch {
            val url = provider.baseUrl.trim()
            val key = c.secrets.key(url).orEmpty()
            val config = registry.value.configs.firstOrNull { it.providerId == provider.id && it.model.isNotBlank() }
            val said = when {
                url.isBlank() -> "还没填接口地址。"
                key.isBlank() -> "这个地址还没有 Key —— 点「编辑」把 Key 填上。"
                config == null -> "这家名下还没有填型号的模型配置，先加一条，再来测。"
                else -> try {
                    c.chatClient.probe(com.cleo.cleos.ai.ApiEndpoint(url, key, config.model))
                    "连上了，说得上话（拿「${config.label}」测的）。"
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { e.message ?: "没连上，也没说为什么。" }
            }
            testResult = provider.id to said
            testing = null
        }
    }

    // ---- 模型配置 ----

    fun addConfig(providerId: String, model: String, name: String, temperature: Float?, maxTokens: Int?) = registryAction {
        require(providerId.isNotBlank()) { "先选一个服务商" }
        val m = model.trim()
        require(m.isNotBlank()) { "型号不能空着" }
        val config = c.registry.addConfig(providerId, m, name)
        if (temperature != null || maxTokens != null) {
            c.registry.updateConfig(config.id) { it.copy(temperature = temperature, maxTokens = maxTokens) }
        }
        "已添加。点「测一下」验验它到底会不会看图 / 看视频。"
    }

    fun updateConfig(id: String, providerId: String, model: String, name: String, temperature: Float?, maxTokens: Int?) = registryAction {
        val m = model.trim()
        require(m.isNotBlank()) { "型号不能空着" }
        c.registry.updateConfig(id) { it.copy(providerId = providerId, model = m, name = name.trim(), temperature = temperature, maxTokens = maxTokens) }
        "已保存"
    }

    /** 删一条模型配置：引用它的功能退回全局默认。 */
    fun removeConfig(id: String) = registryAction {
        val affected = c.registry.removeConfig(id)
        "已删除这条模型配置" + if (affected.isEmpty()) "。" else "；${affected.joinToString("、") { it.label }} 回到跟随全局默认。"
    }

    /**
     * 「测一下」：照 Operit 的做法，**测它真实要干的那件事** ——
     * 看图就画一张印着随机码的图、看视频就现造一小段带码的视频，**答对了才把能力记上**。
     */
    fun testConfig(config: com.cleo.cleos.data.ModelConfig, capability: com.cleo.cleos.data.Capability) {
        if (testing != null) return
        val provider = registry.value.providers.firstOrNull { it.id == config.providerId }
        if (provider == null) {
            capabilityResult = config.id to com.cleo.cleos.ai.ProbeResult(
                passed = false,
                message = "这条配置指向的服务商已经不在了 —— 先修好它再测。",
                outcome = com.cleo.cleos.ai.ProbeOutcome.FAILED,
            )
            return
        }
        testing = config.id
        testResult = null
        viewModelScope.launch {
            val result = when (capability) {
                com.cleo.cleos.data.Capability.Image -> c.capabilityTester.testImage(provider.baseUrl, config.model)
                com.cleo.cleos.data.Capability.Video -> c.capabilityTester.testVideo(provider.baseUrl, config.model)
            }
            if (result.passed) c.registry.setCapability(config.id, capability, true)
            capabilityResult = config.id to result
            testing = null
        }
    }

    // ---- 功能绑定 ----

    fun bindFeature(
        feature: com.cleo.cleos.data.db.Feature,
        configId: String,
        overrides: com.cleo.cleos.data.ParamOverrides = com.cleo.cleos.data.ParamOverrides(),
    ) = registryAction {
        c.registry.bind(feature, configId, overrides)
        if (configId.isBlank()) "「${feature.label}」现在跟着全局默认。" else "「${feature.label}」已绑定。"
    }

    fun setDefaultConfig(configId: String) = registryAction {
        c.registry.setDefault(configId)
        "全局默认换成「${registry.value.configs.firstOrNull { it.id == configId }?.label ?: configId}」了。"
    }

    /** 这个功能现在这条绑定缺哪些能力（见 [com.cleo.cleos.data.ModelRoutes.capabilityProblem]）。 */
    fun capabilityProblem(feature: com.cleo.cleos.data.db.Feature): String? {
        val data = registry.value
        return com.cleo.cleos.data.ModelRoutes.capabilityProblem(
            data, feature, com.cleo.cleos.data.ModelRoutes.configIdFor(data, feature),
        )
    }

    // ---- 批量添加模型 ----

    /** 批量面板在给哪条服务商拉型号。 */
    var batchProviderId by mutableStateOf("")
        private set
    /** 拉到的型号（null = 还没拉）。 */
    var batchModels by mutableStateOf<List<String>?>(null)
        private set
    var batchLoading by mutableStateOf(false)
        private set
    /** 拉取结果 / 失败说明。 */
    var batchMessage by mutableStateOf<String?>(null)
        private set
    /** 勾着的型号。 */
    var batchPicked by mutableStateOf<Set<String>>(emptySet())
        private set
    var batchSearch by mutableStateOf("")
        private set
    var batchSaving by mutableStateOf(false)
        private set
    /** 上一次批量保存的结果（成功/失败分开列）。 */
    var batchResult by mutableStateOf<String?>(null)
        private set
    /** 上一次没保存成功的型号（重试用）。 */
    var batchFailed by mutableStateOf<List<String>>(emptyList())
        private set

    /** 这条服务商名下已经有哪些型号（用来标「已添加」、默认不勾、去重）。 */
    fun configsOfProvider(providerId: String): Set<String> =
        registry.value.configs.filter { it.providerId == providerId }.map { it.model }.toSet()

    fun openBatch(providerId: String) {
        batchProviderId = providerId
        batchModels = null
        batchMessage = null
        batchPicked = emptySet()
        batchSearch = ""
        batchResult = null
        batchFailed = emptyList()
    }

    fun closeBatch() {
        batchProviderId = ""
        batchModels = null
        batchMessage = null
        batchPicked = emptySet()
        batchSearch = ""
        batchResult = null
        batchFailed = emptyList()
    }

    fun updateBatchSearch(text: String) { batchSearch = text }

    /**
     * 从服务商拉型号列表。
     *
     * **只更新这一屏的候选**：不碰已有配置、参数、功能绑定 ——
     * 接口列表里没了的型号也**不会**被删掉。
     */
    fun fetchBatchModels(providerId: String = batchProviderId) {
        if (providerId.isBlank() || batchLoading) return
        batchLoading = true
        batchMessage = null
        batchResult = null
        viewModelScope.launch {
            try {
                val provider = registry.value.providers.firstOrNull { it.id == providerId }
                if (provider == null) {
                    batchMessage = "这条服务商已经不在了"
                    return@launch
                }
                val key = c.secrets.key(provider.baseUrl).orEmpty()
                if (key.isBlank()) {
                    batchMessage = "这个地址还没有 Key —— 先在「服务商」里填上，或者用下面的手动添加"
                    return@launch
                }
                val list = c.chatClient.models(com.cleo.cleos.ai.ApiEndpoint(provider.baseUrl, key, "probe"))
                val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
                batchModels = clean
                val existing = configsOfProvider(providerId)
                // 已添加的不勾；其余默认勾上（人还能自己改）。
                batchPicked = clean.filterNot { it in existing }.toSet()
                batchMessage = if (clean.isEmpty()) {
                    "接口没给型号列表 —— 用下面的「手动添加」粘贴型号"
                } else {
                    "拉到 ${clean.size} 个型号；其中 ${existing.count { it in clean }} 个已经加过了。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                batchModels = null
                batchMessage = "拉取失败：${e.message?.take(200) ?: "没有说明"} —— 可以手动添加"
            } finally {
                batchLoading = false
            }
        }
    }

    fun toggleBatchModel(model: String) {
        batchPicked = if (model in batchPicked) batchPicked - model else batchPicked + model
    }

    fun selectAllBatch() {
        batchPicked = (batchModels.orEmpty() - configsOfProvider(batchProviderId).toSet()).toSet()
    }

    fun clearBatchSelection() { batchPicked = emptySet() }

    /**
     * 一次保存多个型号：**一个型号一条配置**（之后能分别改名、改参数、绑功能）。
     *
     * 按「服务商 + 型号」去重，**不覆盖已有的**；某一条失败只列出来，成功的不再重复添加。
     */
    fun addBatchModels(models: List<String>) {
        if (batchSaving || batchProviderId.isBlank()) return
        val wanted = modelsToAdd(emptySet(), models)
        if (wanted.isEmpty()) return
        batchSaving = true
        batchResult = null
        viewModelScope.launch {
            val providerId = batchProviderId
            val existing = configsOfProvider(providerId).toMutableSet()
            val added = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (model in wanted) {
                if (model in existing) continue
                try {
                    c.registry.addConfig(providerId, model, "")
                    existing += model
                    added += model
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += model
                }
            }
            batchFailed = failed
            batchResult = buildString {
                if (added.isNotEmpty()) append("已添加 ${added.size} 个：").append(added.joinToString("、")).append("。")
                if (failed.isNotEmpty()) {
                    if (added.isNotEmpty()) append("\n")
                    append("没保存成功 ${failed.size} 个：").append(failed.joinToString("、"))
                    append(" —— 可以点「重试失败的」。")
                }
                if (added.isEmpty() && failed.isEmpty()) append("没有新增：勾的都已经在列表里了。")
            }
            batchPicked = batchPicked - added.toSet()
            batchSaving = false
        }
    }

    fun addBatchPicked() = addBatchModels(batchPicked.toList())

    fun retryFailedBatch() {
        if (batchFailed.isEmpty()) return
        addBatchModels(batchFailed)
    }

    /** 手动粘贴多个型号（每行一个）：接口不给列表、或者拉取失败时的退路。 */
    fun addManualModels(text: String) {
        addBatchModels(parseModelIds(text))
    }

    /** 全局默认的「我」的头像；角色没设专属时就显示它（见 [com.cleo.cleos.data.UserFaces]）。 */
    val myAvatarDefault: String? get() = settings.value.userAvatar

    /** 与当前角色聊天时「我」的头像（界面显示用）；null = 用全局默认。 */
    fun myAvatarForCompanion(id: Long = companionId): String? =
        companions.value.firstOrNull { it.id == id }?.userAvatar?.takeIf { it.isNotBlank() }

    /** 给当前角色设一张专属的「我」的头像：**只影响这个角色**，不碰全局和其他角色。 */
    fun setMyAvatarForCompanion(bitmap: android.graphics.Bitmap) {
        val id = companionId
        if (id <= 0L) return
        viewModelScope.launch {
            val file = runCatching { c.images.save(bitmap, prefix = "me-ta-") }.getOrNull() ?: return@launch
            val old = companions.value.firstOrNull { it.id == id }?.userAvatar
            c.companions.update(id) { it.copy(userAvatar = file) }
            // 换下来的那张没人用了，删掉省地方。
            if (!old.isNullOrBlank()) runCatching { c.images.delete(listOf(old)) }
        }
    }

    /** 恢复用全局默认头像（只清这个角色的）。 */
    fun clearMyAvatarForCompanion() {
        val id = companionId
        if (id <= 0L) return
        viewModelScope.launch {
            val old = companions.value.firstOrNull { it.id == id }?.userAvatar
            c.companions.update(id) { it.copy(userAvatar = null) }
            if (!old.isNullOrBlank()) runCatching { c.images.delete(listOf(old)) }
        }
    }
    override fun onCleared() {
        speechPlayer?.release()
        speechPlayer = null
        c.appScope.launch {
            persist()
        }
    }
}
