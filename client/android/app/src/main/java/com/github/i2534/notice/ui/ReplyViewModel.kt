package com.github.i2534.notice.ui

import android.app.Application
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.i2534.notice.R
import com.github.i2534.notice.data.MediaCacheDao
import com.github.i2534.notice.data.MediaCacheEntity
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.MqttSettings
import com.github.i2534.notice.data.RecentTopicStore
import com.github.i2534.notice.data.TopicResolver
import com.github.i2534.notice.service.MqttService
import com.github.i2534.notice.util.BannerType
import com.github.i2534.notice.util.MediaCacheConstants
import com.github.i2534.notice.util.MessageBanner
import com.github.i2534.notice.util.uploadMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

class ReplyViewModel(
    private val application: Application,
    private val mqttService: MqttService,
    private val configStore: MqttConfigStore,
    private val mediaCacheDao: MediaCacheDao,
    recentTopicStore: RecentTopicStore
) : AndroidViewModel(application) {

    private val topicResolver = TopicResolver()
    private var currentSettings = MqttSettings()

    private val _state = MutableStateFlow(ReplyState())
    val state: StateFlow<ReplyState> = _state.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordFile: File? = null

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(recentTopics = recentTopicStore.getRecentTopics())
        }
        viewModelScope.launch {
            configStore.settings.collect { settings ->
                currentSettings = settings
                _state.value = _state.value.copy(
                    defaultTopicDisplay = topicResolver.getDefaultDisplayText(settings.sendTopic, settings.topic)
                )
            }
        }
    }

    fun toggleReplySection() {
        _state.value = _state.value.copy(isReplySectionVisible = !_state.value.isReplySectionVisible)
    }

    fun onContentChanged(content: String) {
        _state.value = _state.value.copy(content = content, error = null)
    }

    fun toggleVoiceMode() {
        pendingVoiceFileCleanup()
        _state.value = _state.value.copy(isVoiceMode = !_state.value.isVoiceMode, content = "")
    }

    private fun pendingVoiceFileCleanup() {
        _state.value.pendingVoiceFile?.let { f ->
            try { f.delete() } catch (_: Exception) { }
        }
        _state.value = _state.value.copy(hasPendingVoice = false, pendingVoiceFile = null)
    }

    fun replyToMessageTopic(topic: String) {
        _state.value = _state.value.copy(
            replyToTopic = topic,
            replyTopicMode = ReplyTopicMode.FromMessage,
            isReplySectionVisible = true
        )
    }

    fun selectDefaultTopic() {
        _state.value = _state.value.copy(
            replyToTopic = null, replyTopicMode = ReplyTopicMode.Default, isTopicPickerVisible = false
        )
    }

    fun selectTopic(topic: String) {
        _state.value = _state.value.copy(
            replyToTopic = topic, replyTopicMode = ReplyTopicMode.Custom, isTopicPickerVisible = false
        )
    }

    fun setCustomTopic(topic: String) {
        if (topic.isNotBlank()) {
            _state.value = _state.value.copy(
                replyToTopic = topic, replyTopicMode = ReplyTopicMode.Custom, isTopicPickerVisible = false
            )
        }
    }

    fun clearReplyTopic() {
        _state.value = _state.value.copy(replyToTopic = null, replyTopicMode = ReplyTopicMode.Default)
    }

    fun getCurrentDefaultTopic(): String? {
        return topicResolver.getDefaultDisplayText(currentSettings.sendTopic, currentSettings.topic)
    }

    fun toggleTopicPicker() {
        _state.value = _state.value.copy(isTopicPickerVisible = !_state.value.isTopicPickerVisible)
    }

    fun hideTopicPicker() {
        _state.value = _state.value.copy(isTopicPickerVisible = false)
    }

    fun sendReply() {
        if (_state.value.hasPendingVoice) { sendPendingVoice(); return }
        val content = _state.value.content.trim()
        if (content.isEmpty()) { MessageBanner.showRes(application, R.string.reply_hint, BannerType.Warning); return }
        sendTextReply(content)
    }

    private fun sendTextReply(content: String) {
        val topic = resolveTopic()
        if (topic.isNullOrBlank()) { MessageBanner.showRes(application, R.string.reply_failed_no_topic, BannerType.Error); return }
        val sent = mqttService.publishReply(content, topic)
        if (sent) {
            _state.value = _state.value.copy(
                content = "", replyToTopic = null, replyTopicMode = ReplyTopicMode.Default
            )
            MessageBanner.showRes(application, R.string.reply_sent, BannerType.Success)
        } else {
            MessageBanner.showRes(application, R.string.reply_failed_not_connected, BannerType.Error)
        }
    }

    private fun sendPendingVoice() {
        val pendingVoice = _state.value.pendingVoiceFile ?: return
        val topic = resolveTopic() ?: run {
            MessageBanner.showRes(application, R.string.reply_failed_no_topic, BannerType.Error)
            return
        }

        viewModelScope.launch {
            val mediaUrl = uploadMedia(currentSettings.serverUrl, currentSettings.authToken, pendingVoice)
            try { pendingVoice.delete() } catch (_: Exception) { }

            if (mediaUrl != null) {
                cacheMediaLocally(mediaUrl, pendingVoice)
                if (mqttService.publishReply(mediaUrl, topic)) {
                    _state.value = _state.value.copy(
                        hasPendingVoice = false, pendingVoiceFile = null,
                        replyToTopic = null, replyTopicMode = ReplyTopicMode.Default
                    )
                    MessageBanner.showRes(application, R.string.reply_sent, BannerType.Success)
                } else {
                    MessageBanner.showRes(application, R.string.reply_failed_not_connected, BannerType.Error)
                }
            } else {
                MessageBanner.showRes(application, R.string.voice_upload_failed, BannerType.Error)
            }
        }
    }

    private suspend fun cacheMediaLocally(mediaUrl: String, sourceFile: File) {
        val cacheDir = File(application.filesDir, MediaCacheConstants.DIR_NAME)
        cacheDir.mkdirs()
        val md5 = MessageDigest.getInstance("MD5").digest(mediaUrl.toByteArray(Charsets.UTF_8))
        val name = md5.take(16).joinToString("") { String.format("%02x", it) } + ".m4a"
        val dest = File(cacheDir, name)
        sourceFile.copyTo(dest, overwrite = true)
        mediaCacheDao.insert(MediaCacheEntity(mediaUrl, dest.absolutePath))
    }

    fun startRecording() {
        viewModelScope.launch {
            if (currentSettings.serverUrl.isBlank()) {
                MessageBanner.showRes(application, R.string.voice_need_server_url, BannerType.Warning)
                return@launch
            }
            val file = File(application.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            try {
                mediaRecorder = MediaRecorder(application).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                currentRecordFile = file
                _state.value = _state.value.copy(isRecording = true)
            } catch (e: Exception) {
                file.delete()
                MessageBanner.showRes(application, R.string.voice_record_failed, BannerType.Error)
            }
        }
    }

    fun stopRecording() {
        val recorder = mediaRecorder
        val file = currentRecordFile
        mediaRecorder = null
        currentRecordFile = null
        _state.value = _state.value.copy(isRecording = false)
        if (recorder == null || file == null) return
        try { recorder.stop() } catch (_: Exception) { }
        recorder.release()
        _state.value = _state.value.copy(hasPendingVoice = true, pendingVoiceFile = file)
    }

    fun cancelRecording() {
        mediaRecorder?.let { r -> try { r.stop() } catch (_: Exception) { }; r.release() }
        currentRecordFile?.delete()
        mediaRecorder = null
        currentRecordFile = null
        _state.value = _state.value.copy(isRecording = false, hasPendingVoice = false, pendingVoiceFile = null)
    }

   private fun resolveTopic(): String? {
        return when (_state.value.replyTopicMode) {
            ReplyTopicMode.Default -> topicResolver.resolve(null, currentSettings.sendTopic, currentSettings.topic)
            else -> _state.value.replyToTopic
        }
    }
}
