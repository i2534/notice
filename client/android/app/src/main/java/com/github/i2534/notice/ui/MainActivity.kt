package com.github.i2534.notice.ui

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.github.i2534.notice.R
import com.github.i2534.notice.databinding.ActivityMainBinding
import com.github.i2534.notice.service.MqttService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mqttService: MqttService? = null
    private var serviceBound = false

    private val messageAdapter = MessageAdapter()

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

        // 清空按钮
        binding.btnClear.setOnClickListener {
            mqttService?.clearMessages()
            binding.latestMessageCard.visibility = View.GONE
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
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_settings) { _, _ ->
                requestIgnoreBatteryOptimization()
            }
            .setNegativeButton(R.string.battery_optimization_later, null)
            .show()
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

            // 观察消息列表
            lifecycleScope.launch {
                service.messages.collectLatest { messages ->
                    messageAdapter.submitList(messages)
                    binding.emptyText.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
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
