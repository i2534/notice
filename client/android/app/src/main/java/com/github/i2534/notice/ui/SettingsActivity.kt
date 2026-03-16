package com.github.i2534.notice.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.i2534.notice.R
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.MqttSettings
import com.github.i2534.notice.databinding.ActivitySettingsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var configStore: MqttConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            binding.inputSendTopic.setText(settings.sendTopic)
            binding.inputKeepAlive.setText(settings.keepAlive.toString())
            binding.inputAuthToken.setText(settings.authToken)
            binding.inputServerUrl.setText(settings.serverUrl)
            binding.switchAutoConnect.isChecked = settings.autoConnect
        }
    }

    private fun saveSettings() {
        val brokerUrl = binding.inputBrokerUrl.text.toString().trim()
        val clientId = binding.inputClientId.text.toString().trim()
        val topic = binding.inputTopic.text.toString().trim()
        val sendTopic = binding.inputSendTopic.text.toString().trim()
        val keepAlive = binding.inputKeepAlive.text.toString().toIntOrNull() ?: 30
        val authToken = binding.inputAuthToken.text.toString().trim()
        val serverUrl = binding.inputServerUrl.text.toString().trim()
        val autoConnect = binding.switchAutoConnect.isChecked

        if (brokerUrl.isBlank()) {
            binding.inputBrokerUrl.error = getString(R.string.settings_error_broker_required)
            return
        }
        if (!brokerUrl.startsWith("tcp://") &&
            !brokerUrl.startsWith("ssl://") &&
            !brokerUrl.startsWith("ws://") &&
            !brokerUrl.startsWith("wss://")) {
            binding.inputBrokerUrl.error = getString(R.string.settings_error_broker_format)
            return
        }
        if (topic.isBlank()) {
            binding.inputTopic.error = getString(R.string.settings_error_topic_required)
            return
        }

        val settings = MqttSettings(
            brokerUrl = brokerUrl,
            clientId = clientId,
            topic = topic,
            sendTopic = sendTopic,
            keepAlive = keepAlive,
            authToken = authToken,
            serverUrl = serverUrl,
            autoConnect = autoConnect
        )

        lifecycleScope.launch {
            configStore.save(settings)
            Snackbar.make(binding.root, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
            finish()
        }
    }
}
