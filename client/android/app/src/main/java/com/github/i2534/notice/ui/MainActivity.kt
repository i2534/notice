package com.github.i2534.notice.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
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
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.i2534.notice.NoticeApp
import com.github.i2534.notice.R
import com.github.i2534.notice.data.AppDatabase
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.data.RecentTopicStore
import com.github.i2534.notice.databinding.ActivityMainBinding
import com.github.i2534.notice.databinding.DialogMessageDetailBinding
import com.github.i2534.notice.service.MqttService
import com.github.i2534.notice.util.MediaCacheLoader
import com.github.i2534.notice.util.TopicColor
import com.github.i2534.notice.util.MessageBanner
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DIALOG_MAX_HEIGHT_RATIO = 0.85
    }

    private lateinit var binding: ActivityMainBinding
    private val markwon get() = (application as NoticeApp).markwon
    private var mqttService: MqttService? = null
    private var serviceBound = false
    private var configStore: MqttConfigStore? = null
    private var replyViewModel: ReplyViewModel? = null
    private var topicPickerDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    private lateinit var bubbleAdapter: BubbleMessageAdapter
    private var lastBuiltItems: List<MessageListItem> = emptyList()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startMqttService()
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            replyViewModel?.startRecording()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MqttService.LocalBinder
            mqttService = binder.getService()
            serviceBound = true
            observeService()
            val cm = configStore ?: return
            replyViewModel = ReplyViewModel(
                application = application,
                mqttService = binder.getService(),
                configStore = cm,
                mediaCacheDao = AppDatabase.getInstance(this@MainActivity).mediaCacheDao(),
                recentTopicStore = RecentTopicStore(AppDatabase.getInstance(this@MainActivity).messageDao())
            )
            observeReplyState()
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
        topicPickerDialog?.dismiss()
        topicPickerDialog = null
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MessageBanner.dismissAll()
    }

    private fun setupUI() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (bubbleAdapter.isSelectMode) {
                    exitSelectMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

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
                R.id.action_clear -> {
                    showClearAllDialog()
                    true
                }
                R.id.action_select_delete -> {
                    deleteSelectedMessages()
                    true
                }
                R.id.action_select_done -> {
                    exitSelectMode()
                    true
                }
                else -> false
            }
        }

        // FAB 点击展开/收起回复栏
        binding.fabReply.setOnClickListener {
            if (binding.replySection.visibility == View.VISIBLE) {
                replyViewModel?.hideReplySection()
            } else {
                replyViewModel?.showReplySection()
            }
        }

        // 关闭按钮收起回复栏
        binding.btnCloseReply.setOnClickListener {
            replyViewModel?.hideReplySection()
        }

        binding.messageList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        bubbleAdapter = BubbleMessageAdapter(
            markwon = markwon,
            onItemClick = { message -> showMessageDetailDialog(message) },
            onLongClick = { message ->
                bubbleAdapter.enterSelectMode(message)
                true
            },
            onEnterSelectMode = { updateSelectModeUI() },
            onSelectionChanged = { count -> updateSelectionCount(count) }
        )
      binding.messageList.adapter = bubbleAdapter

        binding.replyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                replyViewModel?.onContentChanged(s?.toString() ?: "")
            }
        })
        binding.replyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                replyViewModel?.sendReply()
                true
            } else false
        }

        binding.btnSendReply.setOnClickListener {
            replyViewModel?.sendReply()
        }

        binding.btnInputModeToggleWrap.setOnClickListener { replyViewModel?.toggleVoiceMode() }

        binding.voiceHoldToTalk.setOnTouchListener { _, event ->
            val isRec = replyViewModel?.state?.value?.isRecording == true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isRec) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            replyViewModel?.startRecording()
                        } else {
                            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRec) replyViewModel?.stopRecording()
                    true
                }
                else -> false
            }
        }

        binding.replyToTopicRow.setOnClickListener { replyViewModel?.toggleTopicPicker() }
        binding.btnClearReplyToTopic.setOnClickListener {
            replyViewModel?.clearReplyTopic()
        }
        binding.replyToTopicLabel.setOnClickListener { replyViewModel?.toggleTopicPicker() }
        binding.btnToggleTopicPicker.setOnClickListener { replyViewModel?.toggleTopicPicker() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, ime.bottom)
            insets
        }
    }

    private fun observeReplyState() {
        replyViewModel ?: return
        lifecycleScope.launch {
            replyViewModel!!.state.collectLatest { state ->
                if (state.isReplySectionVisible) {
                    binding.replySection.visibility = View.VISIBLE
                    binding.fabReply.visibility = View.GONE
                    binding.replyInput.requestFocus()
                } else {
                    binding.replySection.visibility = View.GONE
                    binding.fabReply.visibility = View.VISIBLE
                    binding.replyInput.clearFocus()
                }

                // 同步输入框内容（避免 ViewModel 清空时输入框不更新）
                val currentText = binding.replyInput.text?.toString() ?: ""
                if (currentText != state.content) {
                    binding.replyInput.setText(state.content)
                    binding.replyInput.setSelection(state.content.length)
                }

                if (state.isVoiceMode) {
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

                if (!state.isRecording) {
                    binding.voiceHoldToTalk.text = if (state.hasPendingVoice) {
                        getString(R.string.voice_recorded_click_send, 0)
                    } else {
                        getString(R.string.voice_hold_to_talk)
                    }
                }

                if (!state.replyToTopic.isNullOrBlank()) {
                    binding.replyToTopicLabel.text = getString(R.string.reply_to_topic, state.replyToTopic)
                    binding.btnClearReplyToTopic.visibility = View.VISIBLE
                    binding.replyTopicDot.setBackgroundColor(TopicColor.forTopic(state.replyToTopic))
                } else {
                    binding.replyToTopicLabel.text = getString(R.string.reply_to_default)
                    binding.btnClearReplyToTopic.visibility = View.GONE
                    binding.replyTopicDot.setBackgroundColor(TopicColor.forTopic(binding.replyToTopicLabel.text.toString()))
                }

                val canSend = !state.isSending && (state.content.isNotBlank() || state.hasPendingVoice)
                if (binding.btnSendReply.isEnabled != canSend) {
                    binding.btnSendReply.isEnabled = canSend
                }

                if (state.isTopicPickerVisible && topicPickerDialog?.isShowing != true) {
                    showTopicPickerFromState(state)
                }
            }
        }
    }

    private fun showTopicPickerFromState(state: ReplyState) {
        topicPickerDialog?.dismiss()
        topicPickerDialog = showTopicPicker(
            context = this,
            state = state,
            defaultTopic = replyViewModel?.getCurrentDefaultTopic(),
            onDefaultSelected = {},
            onTopicSelected = { topic ->
                replyViewModel?.selectTopic(topic)
                topicPickerDialog = null
            },
            onCustomTopic = { topic ->
                replyViewModel?.setCustomTopic(topic)
                topicPickerDialog = null
            }
        )
        topicPickerDialog?.setOnDismissListener {
            topicPickerDialog = null
            replyViewModel?.hideTopicPicker()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startMqttService()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) showBatteryOptimizationDialog()
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
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
        }
    }

    private fun bindMqttService() {
        val intent = Intent(this, MqttService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeService() {
        mqttService?.let { service ->
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    service.connectionState.collectLatest { state -> updateConnectionUI(state) }
                }
            }

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    service.messagesAsc.collectLatest { messages ->
                        val newItems = BubbleListBuilder.buildMessages(messages)
                        bubbleAdapter.submitDiff(lastBuiltItems, newItems)
                        lastBuiltItems = newItems

                        val isEmpty = messages.isEmpty()
                        binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE

                        binding.messageList.post {
                            if (!isEmpty) {
                                binding.messageList.scrollToPosition(bubbleAdapter.itemCount - 1)
                            }
                        }
                    }
                }
            }

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    service.latestMessage.collectLatest { message ->
                        mqttService?.clearUnreadCount()
                    }
                }
            }
        }
    }

    private fun updateSelectModeUI() {
        val isSelect = bubbleAdapter.isSelectMode
        binding.toolbar.title = if (isSelect) getString(R.string.select_mode_title, bubbleAdapter.getSelectedCount()) else getString(R.string.app_name)
        binding.toolbar.menu.apply {
            findItem(R.id.action_connection).isVisible = !isSelect
            findItem(R.id.action_settings).isVisible = !isSelect
            findItem(R.id.action_about).isVisible = !isSelect
            findItem(R.id.action_logs).isVisible = !isSelect
            findItem(R.id.action_clear).isVisible = !isSelect
            findItem(R.id.action_select_delete).isVisible = isSelect
            findItem(R.id.action_select_done).isVisible = isSelect
        }
    }

    private fun updateSelectionCount(count: Int) {
        binding.toolbar.title = getString(R.string.select_mode_title, count)
    }

    private fun exitSelectMode() {
        bubbleAdapter.exitSelectMode()
        updateSelectModeUI()
    }

    private fun deleteSelectedMessages() {
        val selectedIds = bubbleAdapter.getSelectedIds()
        if (selectedIds.isEmpty()) {
            MessageBanner.showRes(this, R.string.no_message_selected, com.github.i2534.notice.util.BannerType.Warning)
            return
        }
        showConfirmDialog(
            title = getString(R.string.message_delete_title),
            message = getString(R.string.message_delete_selected_confirm, selectedIds.size),
            onConfirm = {
                mqttService?.deleteMessages(selectedIds)
                exitSelectMode()
                MessageBanner.show(this, getString(R.string.messages_deleted, selectedIds.size), com.github.i2534.notice.util.BannerType.Success)
            }
        )
    }

    private fun showClearAllDialog() {
        showConfirmDialog(
            title = getString(R.string.clear_all_title),
            message = getString(R.string.clear_all_confirm),
            onConfirm = {
                mqttService?.clearMessages()
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
        AlertDialog.Builder(this, R.style.Theme_Notice_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .setNegativeButton(negativeText, null)
            .show()
    }

    private fun showMessageDetailDialog(message: NoticeMessage) {
        val detailBinding = DialogMessageDetailBinding.inflate(layoutInflater)

        detailBinding.dialogTitle.text = if (message.title.isNotBlank()) message.title else getString(R.string.message_detail_untitled)
        detailBinding.dialogSentByMe.visibility = View.VISIBLE
        detailBinding.dialogSentByMe.text = if (message.isOutgoing) getString(R.string.message_direction_outgoing) else getString(R.string.message_direction_incoming)
        detailBinding.dialogHeader.setBackgroundResource(
            if (message.isOutgoing) R.drawable.bg_dialog_message_header_outgoing
            else R.drawable.bg_dialog_message_header
        )
        val asrOutgoingDisplay = BubbleMessageAdapter.displayTextForOutgoingAsrCommand(this, message.isOutgoing, message.content)
        val blocks = if (asrOutgoingDisplay != null) {
            listOf(ContentBlock.Text(asrOutgoingDisplay))
        } else {
            ContentBlockParser.parse(message.content)
        }
        val hasMediaOrImage = asrOutgoingDisplay == null &&
            ContentBlockParser.extractMediaAndImageUrls(message.content).isNotEmpty()

        fun renderAndShowDialog(mediaCachePathByUrl: Map<String, String>?) {
            MessageContentRenderer.render(
                detailBinding.dialogContentContainer,
                blocks,
                markwon,
                textSelectable = true,
                mediaCachePathByUrl = mediaCachePathByUrl,
                showUrlWhenNoCache = true,
                detailImageMinWidth = null
            )
            detailBinding.dialogTopic.text = message.topic
            detailBinding.dialogTime.text = message.getFormattedTime()
            val dialog = BottomSheetDialog(this, R.style.Theme_Notice_BottomSheet)
            dialog.setContentView(detailBinding.root)
            detailBinding.btnCopy.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(message.title, message.content))
                MessageBanner.showRes(this, R.string.message_detail_copied, com.github.i2534.notice.util.BannerType.Success)
                dialog.dismiss()
            }
            detailBinding.btnReplyToTopic.setOnClickListener {
                replyViewModel?.replyToMessageTopic(message.topic)
                dialog.dismiss()
                MessageBanner.showRes(this, R.string.reply_topic_set, com.github.i2534.notice.util.BannerType.Success)
            }
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                val maxHeight = (resources.displayMetrics.heightPixels * DIALOG_MAX_HEIGHT_RATIO).toInt()
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, maxHeight)
            }
            dialog.show()
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            detailBinding.dialogContentContainer.post {
                val contentWidth = detailBinding.dialogContentContainer.width
                if (contentWidth > 0) {
                    for (i in 0 until detailBinding.dialogContentContainer.childCount) {
                        detailBinding.dialogContentContainer.getChildAt(i)
                            ?.findViewById<ImageView>(R.id.imageBlockImage)
                            ?.minimumWidth = contentWidth
                    }
                }
            }
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
        val (statusTitleRes, colorRes) = when (state) {
            MqttService.ConnectionState.DISCONNECTED -> R.string.status_disconnected to R.color.status_disconnected
            MqttService.ConnectionState.CONNECTING -> R.string.status_connecting to R.color.status_connecting
            MqttService.ConnectionState.CONNECTED -> R.string.status_connected to R.color.status_connected
        }
        binding.toolbar.menu.findItem(R.id.action_connection)?.let { item ->
            item.title = getString(statusTitleRes)
            item.icon?.setTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(colorRes, theme)
            ))
        }
    }
}
