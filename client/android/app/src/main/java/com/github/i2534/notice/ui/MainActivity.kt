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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.github.i2534.notice.R
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.databinding.ActivityMainBinding
import com.github.i2534.notice.service.MqttService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mqttService: MqttService? = null
    private var serviceBound = false

    private val messageAdapter = MessageAdapter(
        onItemClick = { message ->
            showMessageDetailDialog(message)
        },
        onEnterSelectMode = {
            updateSelectModeUI()
        },
        onSelectionChanged = { count ->
            updateSelectionCount(count)
        }
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startMqttService()
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

        setupUI()
        checkNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        bindMqttService()
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

        // RecyclerView
        binding.messageList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }

        // 连接按钮
        binding.btnConnect.setOnClickListener {
            mqttService?.let { service ->
                when (service.connectionState.value) {
                    MqttService.ConnectionState.DISCONNECTED -> service.connect()
                    MqttService.ConnectionState.CONNECTED -> service.disconnect()
                    MqttService.ConnectionState.CONNECTING -> { /* 忽略 */ }
                }
            }
        }

        // 清空按钮（正常模式：清空全部，多选模式：删除选中）
        binding.btnClear.setOnClickListener {
            if (messageAdapter.isSelectMode) {
                deleteSelectedMessages()
            } else {
                showClearAllDialog()
            }
        }

        // 点击最新消息卡片清除未读计数
        binding.latestMessageCard.setOnClickListener {
            mqttService?.clearUnreadCount()
        }
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
                    val isEmpty = loadStates.refresh is androidx.paging.LoadState.NotLoading &&
                            messageAdapter.itemCount == 0
                    binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
                }
            }

            // 观察最新消息
            lifecycleScope.launch {
                service.latestMessage.collectLatest { message ->
                    binding.latestMessageCard.visibility = View.VISIBLE
                    binding.latestTitle.text = message.title
                    binding.latestContent.text = message.content
                    binding.latestTime.text = message.getFormattedTime()
                }
            }

        }
    }

    private fun updateSelectModeUI() {
        binding.btnClear.text = getString(R.string.btn_delete_selected)
        binding.toolbar.title = getString(R.string.select_mode_title, messageAdapter.getSelectedCount())
    }

    private fun updateSelectionCount(count: Int) {
        binding.toolbar.title = getString(R.string.select_mode_title, count)
    }

    private fun exitSelectMode() {
        messageAdapter.exitSelectMode()
        binding.btnClear.text = getString(R.string.clear_history)
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
                binding.latestMessageCard.visibility = View.GONE
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = title
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = message

        val dialog = AlertDialog.Builder(this, R.style.Theme_Notice_Dialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPositive).apply {
            text = positiveText
            // 非破坏性操作使用主色调
            if (!isDestructive) {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary)
            }
            setOnClickListener {
                onConfirm()
                dialog.dismiss()
            }
        }

        dialogView.findViewById<View>(R.id.btnNegative).apply {
            (this as? com.google.android.material.button.MaterialButton)?.text = negativeText
            setOnClickListener {
                dialog.dismiss()
            }
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showMessageDetailDialog(message: NoticeMessage) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_message_detail, null)

        // 绑定数据
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = message.title
        dialogView.findViewById<TextView>(R.id.dialogContent).text = message.content
        dialogView.findViewById<TextView>(R.id.dialogTopic).text = message.topic
        dialogView.findViewById<TextView>(R.id.dialogTime).text = message.getFormattedTime()

        val dialog = AlertDialog.Builder(this, R.style.Theme_Notice_Dialog)
            .setView(dialogView)
            .create()

        // 关闭按钮
        dialogView.findViewById<View>(R.id.btnClose).setOnClickListener {
            dialog.dismiss()
        }

        // 复制按钮
        dialogView.findViewById<View>(R.id.btnCopy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(message.title, message.content)
            clipboard.setPrimaryClip(clip)
            Snackbar.make(binding.root, R.string.message_detail_copied, Snackbar.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateConnectionUI(state: MqttService.ConnectionState) {
        val (statusText, statusColor, buttonText, buttonEnabled) = when (state) {
            MqttService.ConnectionState.DISCONNECTED -> {
                arrayOf(
                    getString(R.string.status_disconnected),
                    ContextCompat.getColor(this, R.color.status_disconnected),
                    getString(R.string.btn_connect),
                    true
                )
            }
            MqttService.ConnectionState.CONNECTING -> {
                arrayOf(
                    getString(R.string.status_connecting),
                    ContextCompat.getColor(this, R.color.status_connecting),
                    getString(R.string.status_connecting),
                    false
                )
            }
            MqttService.ConnectionState.CONNECTED -> {
                arrayOf(
                    getString(R.string.status_connected),
                    ContextCompat.getColor(this, R.color.status_connected),
                    getString(R.string.btn_disconnect),
                    true
                )
            }
        }

        binding.statusText.text = statusText as String
        binding.btnConnect.text = buttonText as String
        binding.btnConnect.isEnabled = buttonEnabled as Boolean

        // 更新状态指示器颜色
        (binding.statusIndicator.background as? GradientDrawable)?.setColor(statusColor as Int)
            ?: run {
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(statusColor as Int)
                }
                binding.statusIndicator.background = drawable
            }
    }
}
