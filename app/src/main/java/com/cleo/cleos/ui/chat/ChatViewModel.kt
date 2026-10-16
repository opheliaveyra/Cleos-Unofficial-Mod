package com.cleo.cleos.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.Recap
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.data.MessageFile
import com.cleo.cleos.ai.Prompt
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageQuote
import com.cleo.cleos.data.MessageQuotes
import com.cleo.cleos.data.Pats
import com.cleo.cleos.data.PickedSticker
import com.cleo.cleos.data.StickerException
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.StickerEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    /** What to draw live below the stored messages; text already stored is blanked out. */
    val streaming: StreamingReply? = null,
    /** A reply is under way here (the stop button), including while its tools run. */
    val replying: Boolean = false,
    val hasApiKey: Boolean = true,
    /** The TA this conversation is with. */
    val companionId: Long = 0,
    val aiName: String = "",
    val userName: String = "",
    val aiAvatar: String? = null,
    val aiAvatarEmoji: String? = null,
    val userAvatar: String? = null,
    val chatAvatars: Boolean = false,
    val avatarEachMessage: Boolean = false,
    /** How big the chat's text is (ChatType). */
    val chatTextSize: Int = ChatType.DEFAULT,
    /** 拍一拍 (Pats): the verb, what follows the TA's name, and whether the phone buzzes. */
    val patVerb: String = Pats.VERB,
    val patSuffix: String = "",
    val patBuzz: Boolean = true,
    val model: String = "",
    /** What the TA keeps of the messages no longer sent verbatim. */
    val recap: String? = null,
    /** The last message folded into [recap]: its time and id. */
    val recapUntil: Pair<Long, Long>? = null,
    /** Voice messages being turned into text. */
    val transcribing: Set<Long> = emptySet(),
    /** A transcription service is set up, so the microphone can be used. */
    val voiceReady: Boolean = false,
    /** The TA has a voice (TA 的声音), so it can talk on the phone. */
    val speechReady: Boolean = false,
    /** Whether the TA may search the web; the switch for it sits in the input bar. */
    val webSearch: Boolean = false,
    /** 这个 TA 主模型用的接口地址；顶栏那个"换模型"列表靠它判断哪一项正在用。 */
    val modelUrl: String = "",
    /** 对话里的小玩意儿：它写的 HTML 直接在气泡里跑起来（设置里的开关，默认关）。 */
    val liveWidgets: Boolean = false,
    /** 它写的卡片：只说"要什么"、样式由 App 画，没有代码在跑（默认开）。 */
    val cards: Boolean = true,
    val loaded: Boolean = false,
)

/** A sticker in the naming dialog: a picture just picked, or one already in the collection. */
sealed interface StickerDraft {
    data class New(val picked: PickedSticker) : StickerDraft

    data class Edit(val sticker: StickerEntity) : StickerDraft
}

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val conversationId = MutableStateFlow<Long?>(null)

    init {
        // The conversation shown is always the current TA's: switching TA opens their latest.
        viewModelScope.launch {
            combine(c.settings.currentConversation, c.companions.current.map { it.id }.distinctUntilChanged()) { remembered, ta ->
                remembered to ta
            }.collect { (remembered, ta) ->
                val id = c.chat.resolveConversation(remembered, ta)
                if (id != remembered) c.settings.setCurrentConversation(id)
                // A quote belongs to the conversation it was picked in, typing to the one it was
                // typed in — and so do the pictures and files that were picked but not sent yet.
                conversationId.value?.takeIf { it != id }?.let {
                    quoting = null
                    // 没发出去的图片和文件也跟着前一个会话走。留着的话，切到另一个 TA 打一行字
                    // 按发送，它们会连字一起发给**另一个人** —— 引用清了，附件忘了清。
                    clearAttachments()
                    c.chat.typing(it, false)
                }
                conversationId.value = id
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChatUiState> = conversationId.filterNotNull().flatMapLatest { id ->
        // The TA of the conversation on screen. Right after a switch that is still the
        // previous TA, until their latest conversation has been found.
        val here = combine(c.db.conversations().observe(id), c.companions.all) { conversation, list ->
            (list.firstOrNull { it.id == conversation?.companionId } ?: list.firstOrNull())?.let { conversation to it }
        }.filterNotNull()
        combine(
            c.db.messages().observe(id),
            combine(c.chat.streaming, c.chat.transcribing) { streaming, transcribing -> streaming to transcribing },
            here,
            here.map { it.second.apiBaseUrl }.distinctUntilChanged().flatMapLatest { c.secrets.hasKey(it) },
            c.settings.settings,
        ) { messages, (streaming, transcribing), (conversation, ta), hasKey, s ->
            val live = streaming[id]
            // Once the stored copy of the live text is in the list, the live one steps
            // aside: all of it when the reply is over, only the text while tools still run.
            val stored = live?.savedId != null && messages.any { it.id == live.savedId }
            ChatUiState(
                conversationId = id,
                messages = messages,
                streaming = when {
                    live == null -> null
                    stored && live.finished -> null
                    stored -> live.copy(text = "")
                    else -> live
                },
                replying = live != null && !live.finished,
                hasApiKey = hasKey,
                companionId = ta.id,
                aiName = ta.name,
                userName = s.userName,
                aiAvatar = ta.avatar,
                aiAvatarEmoji = ta.avatarEmoji,
                userAvatar = s.userAvatar,
                chatAvatars = s.chatAvatars,
                avatarEachMessage = s.avatarEachMessage,
                chatTextSize = s.chatTextSize,
                patVerb = s.patVerb,
                patSuffix = s.patSuffix,
                patBuzz = s.patBuzz,
                model = ta.apiModel,
                modelUrl = ta.apiBaseUrl,
                recap = conversation?.recap,
                recapUntil = conversation?.let { cv -> cv.recapUntilAt?.let { at -> at to (cv.recapUntilId ?: Long.MAX_VALUE) } },
                transcribing = transcribing,
                voiceReady = s.voiceBaseUrl.isNotBlank() && s.voiceModel.isNotBlank(),
                speechReady = Speech.ready(s),
                webSearch = ToolGroup.Search in s.tools,
                liveWidgets = ToolGroup.Live in s.tools,
                cards = ToolGroup.Cards in s.tools,
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    /** Pictures picked for the next message, already copied into the app's storage. */
    val attachments = mutableStateListOf<MessageImage>()
    var attaching by mutableStateOf(false)
        private set

    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            attaching = true
            for (uri in uris.take(Prompt.MAX_IMAGES - attachments.size)) {
                runCatching { c.images.import(uri, maxEdge = 2048, prefix = "chat-") }
                    .onSuccess { attachments += MessageImage(it.file, it.width, it.height) }
            }
            attaching = false
        }
    }

    fun detach(image: MessageImage) {
        attachments.remove(image)
        c.appScope.launch { c.images.delete(listOf(image.file)) }
    }

    /** 上一条视频没能进来的原因；显示一下就消失，跟 [fileProblem] 一样。 */
    var videoProblem by mutableStateOf<String?>(null)
        private set

    fun clearVideoProblem() { videoProblem = null }

    /**
     * 一条视频：**本地抽几帧，当成几张图片**发过去（见 [com.cleo.cleos.data.VideoFrames]）。
     *
     * 为什么不把视频本身发给模型：没有几个模型直接吃视频，而且视频几十上百 MB，
     * 塞进请求里不现实。抽帧是本地干的，抽出来的帧走的就是现成的图片管线 ——
     * 配了"看图模型"的话，那些帧会先被转述成文字再交给聊天的模型。
     *
     * 抽不出帧就明说，**别让人以为发出去了**。
     */
    fun attachVideo(uri: Uri) {
        viewModelScope.launch {
            attaching = true
            val frames = runCatching { c.videoFrames(uri) }.getOrDefault(emptyList())
            if (frames.isEmpty()) {
                videoProblem = "这条视频读不出画面（可能不是视频，或者系统解不了），没有发出去"
            } else {
                val room = (Prompt.MAX_IMAGES - attachments.size).coerceAtLeast(0)
                for (frame in frames.take(room)) {
                    val width = frame.width
                    val height = frame.height
                    runCatching { c.images.save(frame, prefix = "video-") }
                        .onSuccess { name -> attachments += MessageImage(name, width, height) }
                    frame.recycle()
                }
                if (room <= 0) videoProblem = "这次已经带够 ${Prompt.MAX_IMAGES} 张图了，视频的帧没放进来"
            }
            attaching = false
        }
    }

    /**
     * Files picked for the next message, already copied in and read (FileStore.add), in the
     * order they were picked. A file whose reading failed stays in the list with the reason on
     * it: the person can send it anyway, and the TA is told it could not be opened.
     */
    val fileAttachments = mutableStateListOf<MessageFile>()

    /** A file is being copied in and read right now. */
    var readingFiles by mutableStateOf(false)
        private set

    /** Why the last file would not come in at all; shown for a moment, like the voice hint. */
    var fileProblem by mutableStateOf<String?>(null)
        private set

    fun attachFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            readingFiles = true
            try {
                for (uri in uris.take(Prompt.MAX_FILES - fileAttachments.size)) {
                    runCatching { c.files.add(uri) }
                        .onSuccess { file ->
                            fileAttachments += file
                            file.error?.let { fileProblem = "《${file.name}》$it" }
                        }
                        .onFailure { e -> fileProblem = e.message ?: "这个文件读不了" }
                }
            } finally {
                readingFiles = false
            }
        }
    }

    fun dropFile(file: MessageFile) {
        fileAttachments.removeAll { it.file == file.file }
        c.appScope.launch { c.files.delete(listOf(file.file)) }
    }

    /**
     * 丢掉还没发出去的图片和文件，连它们的私有副本一起。
     * 换会话、换 TA 的时候必须做这件事：带着走就等于发给另一个人。
     */
    private fun clearAttachments() {
        val files = fileAttachments.map { it.file }
        attachments.clear()
        fileAttachments.clear()
        if (files.isNotEmpty()) c.appScope.launch { c.files.delete(files) }
    }

    /**
     * 把一段话当成对方说的送出去 —— 在卡片上点了某个选项时用。
     *
     * 和 [send] 一样，但只发这句话：输入框里正打着的字、选好的图和文件都留着不动
     * （人在卡片上点一下，不该把手里还没发的东西一起送出去）。
     */
    fun sendText(text: String) {
        val id = conversationId.value ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        c.chat.send(id, body, emptyList(), quoting, null, null, emptyList())
    }

    /** The switch in the input bar: may the TA search the web from here on. */
    fun setWebSearch(on: Boolean) {
        viewModelScope.launch {
            c.settings.update { s ->
                s.copy(tools = if (on) s.tools + ToolGroup.Search else s.tools - ToolGroup.Search)
            }
        }
    }

    /** The problem has been shown; nothing left to say about it. */
    fun clearFileProblem() {
        fileProblem = null
    }

    /** The message the next one answers, picked with 引用; shown above the input until it goes or is dropped. */
    var quoting by mutableStateOf<MessageQuote?>(null)
        private set

    fun quote(message: MessageEntity) {
        quoting = MessageQuotes.of(message)
    }

    fun unquote() {
        quoting = null
    }

    /** Whether something is being written in the input: the TA waits for it, a while. */
    fun typing(now: Boolean, processingMedia: Boolean = false) {
        conversationId.value?.let { c.chat.typing(it, now, processingMedia) }
    }

    /** False when nothing went out: the text, pictures and files stay where they are. */
    fun send(text: String, diaryRequestId: Long? = null, feedShare: com.cleo.cleos.data.FeedShare? = null): Boolean {
        val id = conversationId.value ?: return false
        if (!c.chat.send(id, text, attachments.toList(), quoting, diaryRequestId, feedShare, fileAttachments.toList())) return false
        attachments.clear()
        fileAttachments.clear()
        quoting = null
        return true
    }

    /**
     * A sticker from the drawer, at once, on its own, the way chat apps send them: with the quote
     * waiting if there is one, while typed words and picked pictures stay for the next message.
     */
    fun sendSticker(sticker: StickerEntity): Boolean {
        val id = conversationId.value ?: return false
        if (!c.chat.sendSticker(id, sticker.name, quoting)) return false
        quoting = null
        return true
    }

    /** 拍一拍: pats the TA ([ai]) or the person themself. A line in the chat, and no answer. */
    fun pat(ai: Boolean) {
        val s = state.value
        val id = s.conversationId ?: return
        c.chat.pat(id, if (ai) Pats.AI else Pats.ME, s.patVerb, s.patSuffix)
    }

    fun savePat(verb: String, suffix: String, buzz: Boolean) {
        viewModelScope.launch {
            c.settings.update { it.copy(patVerb = Pats.cleanVerb(verb), patSuffix = Pats.cleanSuffix(suffix), patBuzz = buzz) }
        }
    }

    /** Puts [emoji] on one of the TA's messages, or takes it off again. */
    fun react(messageId: Long, emoji: String) = c.chat.react(messageId, emoji)

    /** A sticker being named (one just picked) or renamed; null when none is. */
    var stickerDraft by mutableStateOf<StickerDraft?>(null)
        private set

    /** What is wrong with the name tried last, shown in the dialog. */
    var stickerProblem by mutableStateOf<String?>(null)
        private set

    /** A picture from the gallery to become a sticker: copied in, then named ([saveSticker]). */
    fun pickSticker(uri: Uri, onFail: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val picked = c.stickers.import(uri)
                stickerProblem = null
                stickerDraft = StickerDraft.New(picked)
            } catch (e: StickerException) {
                onFail(e.message ?: "这张图用不了")
            }
        }
    }

    fun renameSticker(sticker: StickerEntity) {
        stickerProblem = null
        stickerDraft = StickerDraft.Edit(sticker)
    }

    fun saveSticker(name: String, description: String) {
        val draft = stickerDraft ?: return
        viewModelScope.launch {
            val problem = when (draft) {
                is StickerDraft.New -> c.stickers.add(draft.picked, name, description)
                is StickerDraft.Edit -> c.stickers.edit(draft.sticker.id, name, description)
            }
            stickerProblem = problem
            if (problem == null && stickerDraft == draft) stickerDraft = null
        }
    }

    /** The dialog closed without saving: a picture picked for it goes again. */
    fun dropStickerDraft() {
        (stickerDraft as? StickerDraft.New)?.let { c.stickers.discard(it.picked) }
        stickerDraft = null
        stickerProblem = null
    }

    fun deleteSticker(sticker: StickerEntity) {
        viewModelScope.launch { c.stickers.delete(sticker.id) }
    }

    override fun onCleared() {
        conversationId.value?.let { c.chat.typing(it, false) }
        // Picked but never sent: nothing will ever point at these files.
        val unsent = attachments.map { it.file }
        if (unsent.isNotEmpty()) c.appScope.launch { c.images.delete(unsent) }
        val unsentFiles = fileAttachments.map { it.file }
        if (unsentFiles.isNotEmpty()) c.appScope.launch { c.files.delete(unsentFiles) }
        (stickerDraft as? StickerDraft.New)?.let { c.stickers.discard(it.picked) }
    }

    fun stop() {
        conversationId.value?.let { c.chat.stop(it) }
    }

    fun retry(messageId: Long) {
        conversationId.value?.let { c.chat.retry(it, messageId) }
    }

    fun editMessage(message: MessageEntity, text: String, done: (String?) -> Unit) {
        if (conversationId.value != message.conversationId) { done("已切换聊天，请重新打开编辑"); return }
        c.chat.editMessage(message, text) { branch, problem -> viewModelScope.launch {
            if (branch != null && conversationId.value == message.conversationId) c.settings.setCurrentConversation(branch)
            done(problem)
        } }
    }

    fun delete(messageId: Long) = c.chat.deleteMessage(messageId)

    /**
     * A 按住说话 recording turned into words for the input box, instead of being sent as a
     * voice message. [done] gets the text, or null and the reason it could not be heard. The
     * recording is deleted either way: it was a way of typing, and nothing points at it.
     */
    fun transcribeToInput(clip: MessageAudio, done: (String?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val s = c.settings.current()
                if (s.voiceBaseUrl.isBlank() || s.voiceModel.isBlank()) {
                    done(null, "还没接语音转文字的服务，去设置「发语音」里选一个。")
                    return@launch
                }
                val text = c.transcriber.transcribe(s.voiceBaseUrl, s.voiceModel, c.images.file(clip.file)).trim()
                if (text.isEmpty()) done(null, "没听清，再说一次？") else done(text, null)
            } catch (e: Exception) {
                done(null, e.message ?: "没听清，再说一次？")
            } finally {
                c.images.delete(listOf(clip.file))
            }
        }
    }

    /** Sends a recording, with the quote waiting if there is one. Without a conversation it is thrown away, and false. */
    fun sendVoice(clip: MessageAudio): Boolean {
        val id = state.value.conversationId
        if (id == null) {
            c.images.delete(listOf(clip.file))
            return false
        }
        c.chat.sendVoice(id, clip, quoting)
        quoting = null
        return true
    }

    /** Rings the TA of this conversation; its screen comes up by itself (CleosNavHost). */
    fun call() {
        state.value.conversationId?.let { c.calls.start(it) }
    }

    fun retryVoice(messageId: Long) {
        val id = state.value.conversationId ?: return
        c.chat.retryVoice(id, messageId)
    }

    /** The person's answer to a card asking whether the TA may use an outside service's tool. */
    fun answerAsk(answer: ChatRepository.Answer) {
        val id = state.value.conversationId ?: return
        c.chat.answer(id, answer)
    }

    /** The person's own version of the recap. Emptied, the TA keeps nothing of what came before. */
    fun saveRecap(text: String) {
        val id = state.value.conversationId ?: return
        viewModelScope.launch { c.db.conversations().editRecap(id, text.trim().take(Recap.MAX_STORED).ifEmpty { null }) }
    }

    fun answerSecret(requestMessageId: Long, grant: Boolean) {
        conversationId.value?.let { c.chat.answerSecretRequest(it, requestMessageId, grant) }
    }

    /** An empty conversation is reused instead of stacking up blank ones. */
    fun newConversation() {
        viewModelScope.launch {
            if (state.value.messages.isEmpty() && conversationId.value != null) return@launch
            c.settings.setCurrentConversation(c.chat.newConversation(c.companions.current.first().id))
        }
    }

    /**
     * 点顶部那个模型名能选的东西：主模型、语音模型、保存过的配置。
     *
     * 和设置页那份用的是同一个 `ModelChoices.of`，免得两处各列一套、越走越不一样。
     * 当前是哪个 TA，用和 [state] 一样的办法找（会话 → TA），别另起一套。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val modelChoices: StateFlow<List<com.cleo.cleos.data.ModelChoices.Choice>> =
        conversationId.filterNotNull().flatMapLatest { id ->
            combine(c.db.conversations().observe(id), c.companions.all, c.modelProfiles.profiles) { conversation, list, profiles ->
                val ta = list.firstOrNull { it.id == conversation?.companionId } ?: list.firstOrNull()
                if (ta == null) emptyList() else com.cleo.cleos.data.ModelChoices.of(
                    chatBaseUrl = ta.apiBaseUrl,
                    chatModel = ta.apiModel,
                    spokenOn = ta.spokenModelOn,
                    spokenBaseUrl = ta.spokenApiBaseUrl,
                    spokenModel = ta.spokenApiModel,
                    profiles = profiles,
                    nameOf = { url -> com.cleo.cleos.data.ApiPresets.at(url)?.name },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 换个模型接着聊 —— 顶部那个模型名点一下就能换。
     *
     * 改的是**这个 TA 的主模型**，和设置页里改的是同一处。切完立刻生效：
     * 下一条消息就用新模型，不用重新进设置。
     */
    fun switchModel(companionId: Long, baseUrl: String, model: String) {
        if (companionId <= 0) return
        viewModelScope.launch {
            c.companions.update(companionId) { it.copy(apiBaseUrl = baseUrl.trim(), apiModel = model.trim()) }
        }
    }

    fun switchTo(companionId: Long) {
        viewModelScope.launch { c.companions.select(companionId) }
    }

    /** A new TA, chosen right away; [then] opens their settings to name them and pick a model. */
    fun addCompanion(then: () -> Unit) {
        viewModelScope.launch {
            c.companions.add()
            then()
        }
    }
}
