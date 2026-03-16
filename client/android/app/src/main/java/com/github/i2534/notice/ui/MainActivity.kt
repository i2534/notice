package com.github.i2534.notice.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.media.MediaRecorder
import org.json.JSONObject
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.i2534.notice.NoticeApp
import com.github.i2534.notice.R
import com.github.i2534.notice.data.AppDatabase
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.data.MediaCacheEntity
import com.github.i2534.notice.databinding.ActivityMainBinding
import com.github.i2534.notice.databinding.DialogConfirmBinding
import com.github.i2534.notice.databinding.DialogMessageDetailBinding
import com.github.i2534.notice.service.MqttService
import com.github.i2534.notice.util.MediaCacheConstants
import com.github.i2534.notice.util.MediaCacheLoader
import com.github.i2534.notice.util.uploadMedia
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DIALOG_MAX_HEIGHT_RATIO = 0.85
    }

    private lateinit var binding: ActivityMainBinding
    private val markwon get() = (application as NoticeApp).markwon
    private var mqttService: MqttService? = null
    private var serviceBound = false
    /** 指定回复到的主题（从消息详情点「回复」时设置）；发送后清除 */
    private var replyToTopicOverride: String? = null
    private lateinit var configStore: MqttConfigStore
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordFile: java.io.File? = null
    private var isRecording = false
    /** 待发送的语音文件（松开后不自动上传，点击发送时才上传并发送） */
    private var pendingVoiceFile: java.io.File? = null
    /** true = 语音模式（按住说话），false = 文本模式 */
    private var isVoiceInputMode = false
    /** 本次录音开始时间（用于显示录制秒数） */
    private var voiceRecordingStartMs: Long = 0L

    private val messageAdapter by lazy {
        MessageAdapter(
            markwon,
            onItemClick = { message -> showMessageDetailDialog(message) },
            onEnterSelectMode = { updateSelectModeUI() },
            onSelectionChanged = { count -> updateSelectionCount(count) },
            onAsrConfirm = { topic, text ->
                val service = mqttService
                if (service == null || service.connectionState.value != MqttService.ConnectionState.CONNECTED) {
                    Snackbar.make(binding.root, R.string.reply_failed_not_connected, Snackbar.LENGTH_SHORT).show()
                    return@MessageAdapter
                }
                val payload = JSONObject().apply {
                    put("type", "asr_confirm")
                    put("text", text)
                }.toString()
                if (service.publishReply(payload, topic)) {
                    Snackbar.make(binding.root, R.string.reply_sent, Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, R.string.reply_failed_not_connected, Snackbar.LENGTH_SHORT).show()
                }
            }
        )
    }

    private val headerAdapter by lazy {
        MessageListHeaderAdapter(
            context = this,
            markwon = markwon,
            onLatestCardClick = { mqttService?.clearUnreadCount() },
            onClearClick = {
                if (messageAdapter.isSelectMode) deleteSelectedMessages()
                else showClearAllDialog()
            }
        )
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startMqttService()
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Snackbar.make(binding.root, R.string.voice_need_server_url, Snackbar.LENGTH_SHORT).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MqttService.LocalBinder
            mqttService = binder.getService()
            serviceBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = MqttConfigStore(this)

        // 由我们自行处理系统栏和软键盘 insets，避免输入框被虚拟按键/键盘遮挡
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setupUI()
        ViewCompat.requestApplyInsets(binding.root)
        checkNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        bindMqttService()
    }

    override fun onResume() {
        super.onResume()
        mqttService?.refreshSettings()
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun setupUI() {
        // 返回键处理（多选模式下退出多选）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (messageAdapter.isSelectMode) {
                    exitSelectMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Toolbar
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_connection -> {
                    mqttService?.let { service ->
                        when (service.connectionState.value) {
                            MqttService.ConnectionState.DISCONNECTED -> service.connect()
                            MqttService.ConnectionState.CONNECTED -> service.disconnect()
                            MqttService.ConnectionState.CONNECTING -> { }
                        }
                    }
                    true
                }
                R.id.action_reply -> {
                    toggleReplySection()
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                    true
                }
                R.id.action_logs -> {
                    startActivity(Intent(this, LogsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        // RecyclerView：头部（最新消息+历史标题）+ 消息列表，整页单区滚动
        binding.messageList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ConcatAdapter(headerAdapter, messageAdapter)
        }

        // 对话回复栏：不按圆角 outline 裁切子 View，避免麦克风图标被裁
        binding.replyCard.clipToOutline = false
        binding.replyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendReply()
                true
            } else false
        }
        binding.btnSendReply.setOnClickListener { sendReply() }
        binding.btnInputModeToggleWrap.setOnClickListener { toggleVoiceTextMode() }
        setupHoldToTalk()
        binding.btnClearReplyToTopic.setOnClickListener {
            replyToTopicOverride = null
            updateReplyToTopicUI()
        }

        // 根布局：预留状态栏（上）和导航栏（下），避免内容被系统栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        // 主内容区：键盘弹出时底部 padding = 键盘高度，整块内容上移；键盘收起时 padding 恢复为 0，输入栏回到底部
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                ime.bottom
            )
            insets
        }
    }

    private fun toggleReplySection() {
        val isVisible = binding.replySection.visibility == View.VISIBLE
        binding.replySection.visibility = if (isVisible) View.GONE else View.VISIBLE
        if (!isVisible) {
            binding.replyInput.requestFocus()
        } else {
            binding.replyInput.clearFocus()
        }
    }

    private fun updateReplyToTopicUI() {
        val topic = replyToTopicOverride
        if (!topic.isNullOrBlank()) {
            binding.replyToTopicRow.visibility = View.VISIBLE
            binding.replyToTopicLabel.text = getString(R.string.reply_to_topic, topic)
        } else {
            binding.replyToTopicRow.visibility = View.GONE
        }
    }

    private fun sendReply() {
        val pendingVoice = pendingVoiceFile
        if (pendingVoice != null) {
            // 有待发送语音：上传后发送，不要求输入框有文字
            sendPendingVoice(pendingVoice)
            return
        }
        val content = binding.replyInput.text?.toString()?.trim() ?: ""
        if (content.isEmpty()) {
            Snackbar.make(binding.root, R.string.reply_hint, Snackbar.LENGTH_SHORT).show()
            return
        }
        val service = mqttService
        if (service == null || service.connectionState.value != MqttService.ConnectionState.CONNECTED) {
            Snackbar.make(binding.root, R.string.reply_failed_not_connected, Snackbar.LENGTH_SHORT).show()
            return
        }
        val topicToUse = replyToTopicOverride?.trim()?.takeIf { it.isNotEmpty() } ?: service.getPublishTopic()
        if (topicToUse.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.reply_failed_no_topic, Snackbar.LENGTH_SHORT).show()
            return
        }
        val sent = service.publishReply(content, topicToUse)
        if (sent) {
            binding.replyInput.text?.clear()
            replyToTopicOverride = null
            updateReplyToTopicUI()
            Snackbar.make(binding.root, R.string.reply_sent, Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, R.string.reply_failed_not_connected, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun sendPendingVoice(file: java.io.File) {
        val service = mqttService
        if (service == null || service.connectionState.value != MqttService.ConnectionState.CONNECTED) {
            Snackbar.make(binding.root, R.string.reply_failed_not_connected, Snackbar.LENGTH_SHORT).show()
            return
        }
        val topicToUse = replyToTopicOverride?.trim()?.takeIf { it.isNotEmpty() } ?: service.getPublishTopic()
        if (topicToUse.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.reply_failed_no_topic, Snackbar.LENGTH_SHORT).show()
            return
        }
        pendingVoiceFile = null
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = configStore.settings.first()
            val mediaUrl = uploadMedia(settings.serverUrl, settings.authToken, file)
            if (mediaUrl != null) {
                val cacheDir = File(applicationContext.filesDir, MediaCacheConstants.DIR_NAME)
                cacheDir.mkdirs()
                val name = MessageDigest.getInstance("MD5").digest(mediaUrl.toByteArray(Charsets.UTF_8))
                    .take(16).joinToString("") { "%02x".format(it) } + ".m4a"
                val dest = File(cacheDir, name)
                file.copyTo(dest, overwrite = true)
                AppDatabase.getInstance(applicationContext).mediaCacheDao().insert(MediaCacheEntity(mediaUrl, dest.absolutePath))
            }
            try { file.delete() } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                if (mediaUrl != null) {
                    if (service.publishReply(mediaUrl, topicToUse)) {
                        replyToTopicOverride = null
                        updateReplyToTopicUI()
                        Snackbar.make(binding.root, R.string.reply_sent, Snackbar.LENGTH_SHORT).show()
                    } else {
                        Snackbar.make(binding.root, R.string.reply_failed_not_connected, Snackbar.LENGTH_SHORT).show()
                    }
                } else {
                    Snackbar.make(binding.root, R.string.voice_upload_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleVoiceTextMode() {
        isVoiceInputMode = !isVoiceInputMode
        if (isVoiceInputMode) {
            binding.replyInput.visibility = View.GONE
            binding.voiceHoldToTalk.visibility = View.VISIBLE
            binding.btnInputModeToggle.setImageResource(R.drawable.ic_keyboard)
            binding.btnInputModeToggleWrap.contentDescription = getString(R.string.voice_mode_keyboard)
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(binding.replyInput.windowToken, 0)
        } else {
            binding.replyInput.visibility = View.VISIBLE
            binding.voiceHoldToTalk.visibility = View.GONE
            binding.btnInputModeToggle.setImageResource(R.drawable.ic_mic)
            binding.btnInputModeToggleWrap.contentDescription = getString(R.string.voice_btn_label)
        }
    }

    private fun setupHoldToTalk() {
        binding.voiceHoldToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isRecording) tryStartHoldToTalk()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) stopVoiceRecording()
                    true
                }
                else -> false
            }
        }
    }

    private fun tryStartHoldToTalk() {
        lifecycleScope.launch {
            val settings = configStore.settings.first()
            if (settings.serverUrl.isBlank()) {
                Snackbar.make(binding.root, R.string.voice_need_server_url, Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            when {
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                    startVoiceRecording()
                else -> recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceRecording() {
        pendingVoiceFile?.delete()
        pendingVoiceFile = null
        val file = java.io.File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        try {
            val recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            currentRecordFile = file
            isRecording = true
            voiceRecordingStartMs = System.currentTimeMillis()
            binding.voiceHoldToTalk.text = getString(R.string.voice_recording)
            binding.voiceHoldToTalk.setBackgroundResource(R.drawable.bg_hold_to_talk_recording)
        } catch (e: Exception) {
            currentRecordFile = null
            file.delete()
            Snackbar.make(binding.root, R.string.voice_record_failed, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceRecording() {
        val recorder = mediaRecorder
        val file = currentRecordFile
        mediaRecorder = null
        currentRecordFile = null
        isRecording = false
        binding.voiceHoldToTalk.text = getString(R.string.voice_hold_to_talk)
        binding.voiceHoldToTalk.setBackgroundResource(R.drawable.bg_hold_to_talk)
        if (recorder == null || file == null) return
        try {
            recorder.stop()
        } catch (_: Exception) { }
        recorder.release()
        // 不在此处上传：仅保存为待发送，点击发送时才上传并发送
        pendingVoiceFile?.delete()
        pendingVoiceFile = file
        val durationSec = ((System.currentTimeMillis() - voiceRecordingStartMs) / 1000).toInt().coerceAtLeast(0)
        Snackbar.make(binding.root, getString(R.string.voice_recorded_click_send, durationSec), Snackbar.LENGTH_SHORT).show()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startMqttService()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startMqttService()
        }
    }

    private fun startMqttService() {
        val intent = Intent(this, MqttService::class.java)
        startForegroundService(intent)
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            showBatteryOptimizationDialog()
        }
    }

    private fun showBatteryOptimizationDialog() {
        showConfirmDialog(
            title = getString(R.string.battery_optimization_title),
            message = getString(R.string.battery_optimization_message),
            positiveText = getString(R.string.battery_optimization_settings),
            negativeText = getString(R.string.battery_optimization_later),
            isDestructive = false,
            onConfirm = { requestIgnoreBatteryOptimization() }
        )
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // 部分手机不支持，打开电池设置页面
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                // 忽略
            }
        }
    }

    private fun bindMqttService() {
        val intent = Intent(this, MqttService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeService() {
        mqttService?.let { service ->
            // 观察连接状态
            lifecycleScope.launch {
                service.connectionState.collectLatest { state ->
                    updateConnectionUI(state)
                }
            }

            // 观察分页消息列表
            lifecycleScope.launch {
                service.messagesPaging.collectLatest { pagingData ->
                    messageAdapter.submitData(pagingData)
                }
            }

            // 观察列表是否为空
            lifecycleScope.launch {
                messageAdapter.loadStateFlow.collectLatest { loadStates ->
                    val isEmpty = loadStates.refresh is LoadState.NotLoading &&
                            messageAdapter.itemCount == 0
                    binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
                }
            }

            // 观察最新消息（头部在 MessageListHeaderAdapter 内渲染）
            lifecycleScope.launch {
                service.latestMessage.collectLatest { message ->
                    headerAdapter.latestMessage = message
                }
            }

        }
    }

    private fun updateSelectModeUI() {
        headerAdapter.isSelectMode = true
        binding.toolbar.title = getString(R.string.select_mode_title, messageAdapter.getSelectedCount())
    }

    private fun updateSelectionCount(count: Int) {
        binding.toolbar.title = getString(R.string.select_mode_title, count)
    }

    private fun exitSelectMode() {
        messageAdapter.exitSelectMode()
        headerAdapter.isSelectMode = false
        binding.toolbar.title = getString(R.string.app_name)
    }

    private fun deleteSelectedMessages() {
        val selectedIds = messageAdapter.getSelectedIds()
        if (selectedIds.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_message_selected, Snackbar.LENGTH_SHORT).show()
            return
        }

        showConfirmDialog(
            title = getString(R.string.message_delete_title),
            message = getString(R.string.message_delete_selected_confirm, selectedIds.size),
            onConfirm = {
                mqttService?.deleteMessages(selectedIds)
                exitSelectMode()
                Snackbar.make(binding.root, getString(R.string.messages_deleted, selectedIds.size), Snackbar.LENGTH_SHORT).show()
            }
        )
    }

    private fun showClearAllDialog() {
        showConfirmDialog(
            title = getString(R.string.clear_all_title),
            message = getString(R.string.clear_all_confirm),
            onConfirm = {
                mqttService?.clearMessages()
                headerAdapter.latestMessage = null
            }
        )
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        positiveText: String = getString(R.string.message_delete_yes),
        negativeText: String = getString(R.string.message_delete_no),
        isDestructive: Boolean = true,
        onConfirm: () -> Unit
    ) {
        val confirmBinding = DialogConfirmBinding.inflate(layoutInflater)
        confirmBinding.dialogTitle.text = title
        confirmBinding.dialogMessage.text = message

        val dialog = AlertDialog.Builder(this, R.style.Theme_Notice_Dialog)
            .setView(confirmBinding.root)
            .create()

        confirmBinding.btnPositive.apply {
            text = positiveText
            if (!isDestructive) {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary)
            }
            setOnClickListener {
                onConfirm()
                dialog.dismiss()
            }
        }
        confirmBinding.btnNegative.apply {
            text = negativeText
            setOnClickListener { dialog.dismiss() }
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showMessageDetailDialog(message: NoticeMessage) {
        val detailBinding = DialogMessageDetailBinding.inflate(layoutInflater)

        if (message.isOutgoing) {
            detailBinding.dialogTitle.visibility = View.GONE
        } else {
            detailBinding.dialogTitle.visibility = View.VISIBLE
            detailBinding.dialogTitle.text = message.title
        }
        detailBinding.dialogSentByMe.visibility =
            if (message.isOutgoing) View.VISIBLE else View.GONE
        detailBinding.dialogHeader.setBackgroundResource(
            if (message.isOutgoing) R.drawable.bg_dialog_message_header_outgoing
            else R.drawable.bg_dialog_message_header
        )
        val blocks = ContentBlockParser.parse(message.content)
        val hasMediaOrImage = ContentBlockParser.extractMediaAndImageUrls(message.content).isNotEmpty()

        fun renderAndShowDialog(mediaCachePathByUrl: Map<String, String>?) {
            MessageContentRenderer.render(
                detailBinding.dialogContentContainer,
                blocks,
                markwon,
                textSelectable = true,
                mediaCachePathByUrl = mediaCachePathByUrl,
                showUrlWhenNoCache = true
            )
            detailBinding.dialogTopic.text = message.topic
            detailBinding.dialogTime.text = message.getFormattedTime()
            val dialog = AlertDialog.Builder(this, R.style.Theme_Notice_Dialog)
                .setView(detailBinding.root)
                .create()
            detailBinding.btnClose.setOnClickListener { dialog.dismiss() }
            detailBinding.btnCopy.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(message.title, message.content)
                clipboard.setPrimaryClip(clip)
                Snackbar.make(binding.root, R.string.message_detail_copied, Snackbar.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                val maxHeight = (resources.displayMetrics.heightPixels * DIALOG_MAX_HEIGHT_RATIO).toInt()
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    maxHeight
                )
            }
            dialog.show()
            MessageContentRenderer.requestFocusOnFirstVisiblePlayRow(detailBinding.dialogContentContainer)
        }

        if (!hasMediaOrImage) {
            renderAndShowDialog(null)
        } else {
            lifecycleScope.launch {
                val map = MediaCacheLoader.ensureMediaAndImageCache(applicationContext, message.content)
                renderAndShowDialog(if (map.isEmpty()) null else map)
            }
        }
    }

    private fun updateConnectionUI(state: MqttService.ConnectionState) {
        val (statusTitleRes, iconRes) = when (state) {
            MqttService.ConnectionState.DISCONNECTED ->
                R.string.status_disconnected to R.drawable.ic_status_disconnected
            MqttService.ConnectionState.CONNECTING ->
                R.string.status_connecting to R.drawable.ic_status_connecting
            MqttService.ConnectionState.CONNECTED ->
                R.string.status_connected to R.drawable.ic_status_connected
        }
        binding.toolbar.menu.findItem(R.id.action_connection)?.let { item ->
            item.title = getString(statusTitleRes)
            item.setIcon(iconRes)
        }
    }
}
