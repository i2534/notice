package com.github.i2534.notice.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.github.i2534.notice.R
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.MqttSettings
import com.github.i2534.notice.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var configStore: MqttConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = MqttConfigStore(this)

        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_save -> {
                    saveSettings()
                    true
                }
                else -> false
            }
        }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            val settings = configStore.settings.first()
            binding.inputBrokerUrl.setText(settings.brokerUrl)
            binding.inputClientId.setText(settings.clientId)
            binding.inputTopic.setText(settings.topic)
            binding.inputKeepAlive.setText(settings.keepAlive.toString())
            binding.inputAuthToken.setText(settings.authToken)
            binding.switchAutoConnect.isChecked = settings.autoConnect
        }
    }

    private fun saveSettings() {
        val brokerUrl = binding.inputBrokerUrl.text.toString().trim()
        val clientId = binding.inputClientId.text.toString().trim()
        val topic = binding.inputTopic.text.toString().trim()
        val keepAlive = binding.inputKeepAlive.text.toString().toIntOrNull() ?: 30
        val authToken = binding.inputAuthToken.text.toString().trim()
        val autoConnect = binding.switchAutoConnect.isChecked

        // 验证
        if (brokerUrl.isBlank()) {
            binding.inputBrokerUrl.error = "请输入 Broker 地址"
            return
        }

        if (!brokerUrl.startsWith("tcp://") && 
            !brokerUrl.startsWith("ssl://") &&
            !brokerUrl.startsWith("ws://") && 
            !brokerUrl.startsWith("wss://")) {
            binding.inputBrokerUrl.error = "地址格式错误，应以 tcp://, ssl://, ws:// 或 wss:// 开头"
            return
        }

        if (topic.isBlank()) {
            binding.inputTopic.error = "请输入订阅主题"
            return
        }

        val settings = MqttSettings(
            brokerUrl = brokerUrl,
            clientId = clientId,
            topic = topic,
            keepAlive = keepAlive,
            authToken = authToken,
            autoConnect = autoConnect
        )

        lifecycleScope.launch {
            configStore.save(settings)
            Snackbar.make(binding.root, "设置已保存", Snackbar.LENGTH_SHORT).show()
            finish()
        }
    }
}
