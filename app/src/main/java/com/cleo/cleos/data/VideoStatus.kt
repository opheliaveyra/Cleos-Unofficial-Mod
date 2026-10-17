package com.cleo.cleos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一条视频**此刻**的理解状态，给视频气泡下面那行小字用。
 *
 * 只写真实发生过的事：
 * - [Delegated]：确实把这它交给「视频理解」那条模型了（不是"存了配置"就算）
 * - [Understanding]：正在请求
 * - [Done]：结果已经交给聊天模型
 * - [Failed]：真的失败了，[reason] 是给人看的原因
 * - [NoModel]：没配视频理解模型，聊天模型这条路也读不了
 *
 * **能力未知或请求失败不能写成"不支持视频"** —— 所以这里没有"Supported"这种状态。
 */
sealed interface VideoStatus {
    data class Delegated(val model: String) : VideoStatus
    data object Understanding : VideoStatus
    data class Done(val role: String) : VideoStatus
    data class Failed(val reason: String) : VideoStatus
    data object NoModel : VideoStatus
}

/** 视频文件名 → 状态。跟着一次发送走，界面直接订阅。 */
class VideoStatusStore {
    private val _states = MutableStateFlow<Map<String, VideoStatus>>(emptyMap())
    val states: StateFlow<Map<String, VideoStatus>> = _states.asStateFlow()

    fun set(video: String, status: VideoStatus) {
        _states.update { it + (video to status) }
    }

    fun clear(video: String) {
        _states.update { it - video }
    }
}
