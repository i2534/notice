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
    val topic: String = "notice/#",
    val autoConnect: Boolean = true,
    val keepAlive: Int = 30,
    val authToken: String = ""  // 认证 Token
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
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val KEY_KEEP_ALIVE = intPreferencesKey("keep_alive")
        private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
    }

    val settings: Flow<MqttSettings> = context.dataStore.data.map { prefs ->
        MqttSettings(
            brokerUrl = prefs[KEY_BROKER_URL] ?: MqttSettings().brokerUrl,
            clientId = prefs[KEY_CLIENT_ID] ?: "",
            topic = prefs[KEY_TOPIC] ?: MqttSettings().topic,
            autoConnect = prefs[KEY_AUTO_CONNECT] ?: true,
            keepAlive = prefs[KEY_KEEP_ALIVE] ?: 30,
            authToken = prefs[KEY_AUTH_TOKEN] ?: ""
        )
    }

    suspend fun save(settings: MqttSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BROKER_URL] = settings.brokerUrl
            prefs[KEY_CLIENT_ID] = settings.clientId
            prefs[KEY_TOPIC] = settings.topic
            prefs[KEY_AUTO_CONNECT] = settings.autoConnect
            prefs[KEY_KEEP_ALIVE] = settings.keepAlive
            prefs[KEY_AUTH_TOKEN] = settings.authToken
        }
    }
}
