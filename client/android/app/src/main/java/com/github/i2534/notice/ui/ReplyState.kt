package com.github.i2534.notice.ui

import java.io.File

data class ReplyState(
    val content: String = "",
    val isVoiceMode: Boolean = false,
    val replyToTopic: String? = null,
    val replyTopicMode: ReplyTopicMode = ReplyTopicMode.Default,
    val recentTopics: List<String> = emptyList(),
    val defaultTopicDisplay: String? = null,
    val isTopicPickerVisible: Boolean = false,
    val isRecording: Boolean = false,
    val recordingDuration: Int = 0,
    val hasPendingVoice: Boolean = false,
    val pendingVoiceFile: File? = null,
    val isSending: Boolean = false,
    val error: ReplyError? = null,
    val isReplySectionVisible: Boolean = false
) {
    val resolvedPublishTopic: String? get() = when (replyTopicMode) {
        is ReplyTopicMode.Custom -> replyToTopic
        is ReplyTopicMode.FromMessage -> replyToTopic
        is ReplyTopicMode.Default -> null
    }
}

sealed class ReplyTopicMode {
    object Default : ReplyTopicMode()
    object FromMessage : ReplyTopicMode()
    object Custom : ReplyTopicMode()
}

sealed class ReplyError {
    object NotConnected : ReplyError()
    object NoTopicConfigured : ReplyError()
    data class UploadFailed(val message: String) : ReplyError()
    data class PublishFailed(val message: String) : ReplyError()
}
