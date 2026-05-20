package com.github.i2534.notice.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.i2534.notice.BuildConfig
import com.github.i2534.notice.R
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.MqttSettings
import com.github.i2534.notice.databinding.ActivitySettingsBinding
import com.github.i2534.notice.util.MessageBanner
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

        setupToolbar()
        setupClickListeners()
        loadSettings()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupClickListeners() {
        // 连接
        binding.settingBrokerAddress.setOnClickListener { showBrokerDialog() }
        binding.settingAuth.setOnClickListener { showAuthDialog() }
        binding.settingDefaultTopic.setOnClickListener { showTopicDialog() }

        // 通知
        binding.settingPushNotification.setOnClickListener {
            binding.switchPush.isChecked = !binding.switchPush.isChecked
            savePushSetting()
        }
        binding.switchPush.setOnCheckedChangeListener { _, isChecked ->
            binding.settingPushNotification.findViewById<android.view.View>(R.id.settingPushNotification)?.let {
                // Toggle already handled
            }
        }
        binding.settingSound.setOnClickListener {
            binding.switchSound.isChecked = !binding.switchSound.isChecked
            saveSoundSetting()
        }

        // 数据
        binding.settingClearMessages.setOnClickListener { showClearMessagesDialog() }
        binding.settingLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        // 关于
        binding.settingVersion.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            val settings = configStore.settings.first()
            binding.settingBrokerValue.text = settings.brokerUrl
            binding.settingDefaultTopicValue.text = settings.topic
            binding.settingVersionValue.text = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

            // 通知开关
            binding.switchPush.isChecked = settings.pushNotification
            binding.switchSound.isChecked = settings.soundEnabled
        }
    }

    private fun showBrokerDialog() {
        lifecycleScope.launch {
            val current = configStore.settings.first().brokerUrl
            val input = androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_broker_address)
                .setView(R.layout.dialog_edit_text)
                .setPositiveButton(android.R.string.ok, null)
                .create()

            input.show()
            val editText = input.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogEditText)
            editText?.setText(current)
            input.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val value = editText?.text?.toString()?.trim() ?: ""
                if (value.isNotBlank() && (value.startsWith("tcp://") || value.startsWith("ssl://") || value.startsWith("ws://") || value.startsWith("wss://"))) {
                    saveBrokerUrl(value)
                }
                input.dismiss()
            }
        }
    }

    private fun showAuthDialog() {
        lifecycleScope.launch {
            val current = configStore.settings.first().authToken
            val input = androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_auth)
                .setView(R.layout.dialog_edit_text)
                .setPositiveButton(android.R.string.ok, null)
                .create()

            input.show()
            val editText = input.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogEditText)
            editText?.setText(current)
            input.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val value = editText?.text?.toString()?.trim() ?: ""
                saveAuthToken(value)
                input.dismiss()
            }
        }
    }

    private fun showTopicDialog() {
        lifecycleScope.launch {
            val current = configStore.settings.first().topic
            val input = androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_default_topic)
                .setView(R.layout.dialog_edit_text)
                .setPositiveButton(android.R.string.ok, null)
                .create()

            input.show()
            val editText = input.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogEditText)
            editText?.setText(current)
            input.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val value = editText?.text?.toString()?.trim() ?: ""
                if (value.isNotBlank()) {
                    saveDefaultTopic(value)
                }
                input.dismiss()
            }
        }
    }

    private fun saveBrokerUrl(value: String) {
        lifecycleScope.launch {
            val settings = configStore.settings.first()
            configStore.save(settings.copy(brokerUrl = value))
            binding.settingBrokerValue.text = value
            MessageBanner.showRes(this@SettingsActivity, R.string.settings_broker_saved, com.github.i2534.notice.util.BannerType.Success)
        }
    }

    private fun saveAuthToken(value: String) {
        lifecycleScope.launch {
            val settings = configStore.settings.first()
            configStore.save(settings.copy(authToken = value))
            MessageBanner.showRes(this@SettingsActivity, R.string.settings_auth_saved, com.github.i2534.notice.util.BannerType.Success)
        }
    }

    private fun saveDefaultTopic(value: String) {
        lifecycleScope.launch {
            val settings = configStore.settings.first()
            configStore.save(settings.copy(topic = value))
            binding.settingDefaultTopicValue.text = value
            MessageBanner.showRes(this@SettingsActivity, R.string.settings_topic_saved, com.github.i2534.notice.util.BannerType.Success)
        }
    }

    private fun savePushSetting() {
        lifecycleScope.launch {
            val settings = configStore.settings.first()
            configStore.save(settings.copy(pushNotification = binding.switchPush.isChecked))
        }
    }

    private fun saveSoundSetting() {
        lifecycleScope.launch {
            val settings = configStore.settings.first()
            configStore.save(settings.copy(soundEnabled = binding.switchSound.isChecked))
        }
    }

    private fun showClearMessagesDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_title)
            .setMessage(R.string.dialog_clear_message)
            .setPositiveButton(R.string.dialog_clear_confirm) { _, _ ->
                // Clear messages via service
                finish()
            }
            .setNegativeButton(R.string.dialog_clear_cancel, null)
            .show()
    }
}
