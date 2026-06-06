package com.github.i2534.notice.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mqtt_config")

data class MqttSettings(
    val brokerUrl: String = "wss://mqtt.example.com",
    val clientId: String = "",
    /** 订阅主题（接收消息用，如 notice/#） */
    val topic: String = "notice/#",
    /** 上次选择的回复主题，用于预填 */
    val lastSelectedTopic: String = "",
    val autoConnect: Boolean = true,
    val keepAlive: Int = 30,
    val authToken: String = "",  // 认证 Token
    /** Notice 服务器 URL（用于语音/多媒体上传，与 OpenClaw serverUrl 一致；留空则不可上传） */
    val serverUrl: String = "",
    val pushNotification: Boolean = true,
    val soundEnabled: Boolean = true,
    /** 主题模式: 0=跟随系统, 1=浅色, 2=深色 */
    val themeMode: Int = 0
) {
    /**
     * 获取有效的 Client ID
     * @param generateNew 如果为空是否生成新的（首次使用时传 true）
     */
    fun getEffectiveClientId(generateNew: Boolean = false): String {
        return if (clientId.isNotBlank()) {
            clientId
        } else if (generateNew) {
            "android-${UUID.randomUUID().toString().take(8)}"
        } else {
            ""
        }
    }

    fun hasAuth(): Boolean {
        return authToken.isNotBlank()
    }
}

class MqttConfigStore(private val context: Context) {

    companion object {
        private val KEY_BROKER_URL = stringPreferencesKey("broker_url")
        private val KEY_CLIENT_ID = stringPreferencesKey("client_id")
        private val KEY_TOPIC = stringPreferencesKey("topic")
        private val KEY_LAST_SELECTED_TOPIC = stringPreferencesKey("last_selected_topic")
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val KEY_KEEP_ALIVE = intPreferencesKey("keep_alive")
        private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_PUSH_NOTIFICATION = booleanPreferencesKey("push_notification")
        private val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
    }

    val settings: Flow<MqttSettings> = context.dataStore.data.map { prefs ->
        MqttSettings(
            brokerUrl = prefs[KEY_BROKER_URL] ?: MqttSettings().brokerUrl,
            clientId = prefs[KEY_CLIENT_ID] ?: "",
            topic = prefs[KEY_TOPIC] ?: MqttSettings().topic,
            lastSelectedTopic = prefs[KEY_LAST_SELECTED_TOPIC] ?: "",
            autoConnect = prefs[KEY_AUTO_CONNECT] ?: true,
            keepAlive = prefs[KEY_KEEP_ALIVE] ?: 30,
            authToken = prefs[KEY_AUTH_TOKEN] ?: "",
            serverUrl = prefs[KEY_SERVER_URL] ?: "",
            pushNotification = prefs[KEY_PUSH_NOTIFICATION] ?: true,
            soundEnabled = prefs[KEY_SOUND_ENABLED] ?: true,
            themeMode = prefs[KEY_THEME_MODE] ?: 0
        )
    }

    suspend fun save(settings: MqttSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BROKER_URL] = settings.brokerUrl
            prefs[KEY_CLIENT_ID] = settings.clientId
            prefs[KEY_TOPIC] = settings.topic
            prefs[KEY_LAST_SELECTED_TOPIC] = settings.lastSelectedTopic
            prefs[KEY_AUTO_CONNECT] = settings.autoConnect
            prefs[KEY_KEEP_ALIVE] = settings.keepAlive
            prefs[KEY_AUTH_TOKEN] = settings.authToken
            prefs[KEY_SERVER_URL] = settings.serverUrl
            prefs[KEY_PUSH_NOTIFICATION] = settings.pushNotification
            prefs[KEY_SOUND_ENABLED] = settings.soundEnabled
            prefs[KEY_THEME_MODE] = settings.themeMode
        }
    }
}
