package com.github.i2534.notice.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.MqttSettings
import com.github.i2534.notice.receiver.KeepAliveReceiver
import com.github.i2534.notice.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Ref(
    var lastConnectTime: Long = 0L,
    var lastMessageTime: Long = 0L
)

private const val TAG = "MqttConnectionManager"

private suspend fun MqttAsyncClient.connectSuspend(options: MqttConnectOptions): IMqttToken {
    return suspendCancellableCoroutine { cont ->
        connect(options, null, object : IMqttActionListener {
            override fun onSuccess(token: IMqttToken) {
                if (cont.isActive) cont.resume(token)
            }
            override fun onFailure(token: IMqttToken, ex: Throwable) {
                if (cont.isActive) cont.resumeWithException(ex)
            }
        })
        cont.invokeOnCancellation {
            if (isConnected) disconnect()
        }
    }
}

class MqttConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val configStore: MqttConfigStore,
    private val _connectionState: MutableStateFlow<MqttService.ConnectionState>,
    val connectionRef: Ref,
    var userDisconnected: Boolean = false,
    private val onConnected: () -> Unit = {},
    private val onMessage: (topic: String, payload: ByteArray) -> Unit
) {

    var mqttClient: MqttAsyncClient? = null
        private set

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val maxReconnectDelay = 60_000L
    private val baseReconnectDelay = 3_000L
    private val minConnectStableTime = 30 * 60 * 1000L
    private val maxNoMessageTime = 30 * 60 * 1000L

    private var heartbeatJob: Job? = null
    private val heartbeatInterval = 10_000L

    private val keepAliveInterval = 10 * 60 * 1000L
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private val keepAlivePendingIntent by lazy {
        val intent = Intent(context, KeepAliveReceiver::class.java).apply {
            action = MqttService.ACTION_KEEP_ALIVE
        }
        android.app.PendingIntent.getBroadcast(
            context, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                val isDoze = powerManager.isDeviceIdleMode
                if (isDoze) {
                    AppLogger.w(TAG, "Device entered Doze mode")
                } else {
                    AppLogger.i(TAG, "Device exited Doze mode, checking connection...")
                    handleKeepAlive()
                }
            }
        }
    }

    suspend fun connectMqtt(settings: MqttSettings) {
        _connectionState.value = MqttService.ConnectionState.CONNECTING
        AppLogger.d(TAG, "Connecting to ${settings.brokerUrl}")

        try {
            mqttClient?.let {
                if (it.isConnected) it.disconnect()
                it.close()
            }

            var clientId = settings.getEffectiveClientId(generateNew = false)
            if (clientId.isBlank()) {
                clientId = settings.getEffectiveClientId(generateNew = true)
                configStore.save(settings.copy(clientId = clientId))
                AppLogger.d(TAG, "Generated and saved new clientId: $clientId")
            }

            mqttClient = MqttAsyncClient(
                settings.brokerUrl,
                clientId,
                MemoryPersistence()
            )

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    AppLogger.w(TAG, "Connection lost: ${cause?.message}")
                    _connectionState.value = MqttService.ConnectionState.DISCONNECTED
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != null && message != null) {
                        onMessage(topic, message.payload)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val options = MqttConnectOptions().apply {
                isCleanSession = false
                keepAliveInterval = settings.keepAlive
                connectionTimeout = 30
                isAutomaticReconnect = true
                maxInflight = 100
                if (settings.hasAuth()) {
                    userName = settings.authToken
                    AppLogger.d(TAG, "Using token authentication")
                }
            }

            mqttClient?.connectSuspend(options)
            mqttClient?.subscribe(settings.topic, 1)?.waitForCompletion(10000)

            _connectionState.value = MqttService.ConnectionState.CONNECTED
            reconnectAttempt = 0
            connectionRef.lastConnectTime = System.currentTimeMillis()
            AppLogger.d(TAG, "Connected and subscribed to ${settings.topic}")
            onConnected()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Connection failed: ${e.message}", e)
            _connectionState.value = MqttService.ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    suspend fun disconnectMqtt() {
        try {
            mqttClient?.let {
                if (it.isConnected) it.disconnect()?.waitForCompletion(5000)
                it.close()
            }
            mqttClient = null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Disconnect error: ${e.message}")
        }
    }

    fun scheduleReconnect() {
        if (userDisconnected) {
            AppLogger.d(TAG, "User disconnected, skip reconnect")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delay = minOf(baseReconnectDelay * (1L shl reconnectAttempt), maxReconnectDelay)
            reconnectAttempt++
            AppLogger.d(TAG, "Reconnecting in ${delay / 1000}s (attempt $reconnectAttempt)")
            delay(delay)
            if (_connectionState.value == MqttService.ConnectionState.DISCONNECTED && !userDisconnected) {
                val settings = configStore.settings.first()
                connectMqtt(settings)
            }
        }
    }

    fun scheduleKeepAliveAlarm() {
        val triggerTime = SystemClock.elapsedRealtime() + keepAliveInterval
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        keepAlivePendingIntent
                    )
                    AppLogger.d(TAG, "Keep-alive alarm scheduled for ${keepAliveInterval / 60000} minutes")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        keepAlivePendingIntent
                    )
                    AppLogger.w(TAG, "Using inexact alarm (no SCHEDULE_EXACT_ALARM permission)")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    keepAlivePendingIntent
                )
                AppLogger.d(TAG, "Keep-alive alarm scheduled for ${keepAliveInterval / 60000} minutes")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to schedule keep-alive alarm: ${e.message}")
        }
    }

    fun cancelKeepAliveAlarm() {
        alarmManager.cancel(keepAlivePendingIntent)
        AppLogger.d(TAG, "Keep-alive alarm cancelled")
    }

    fun registerDozeReceiver() {
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(dozeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("deprecation")
            context.registerReceiver(dozeReceiver, filter)
        }
        AppLogger.d(TAG, "Doze mode receiver registered")
    }

    fun unregisterDozeReceiver() {
        try {
            context.unregisterReceiver(dozeReceiver)
            AppLogger.d(TAG, "Doze mode receiver unregistered")
        } catch (_: Exception) {
        }
    }

    fun handleKeepAlive() {
        val mqttConnected = mqttClient?.isConnected == true
        val state = _connectionState.value

        AppLogger.d(TAG, "Keep-alive check: state=$state, mqtt=$mqttConnected")

        if (!mqttConnected && !userDisconnected) {
            AppLogger.d(TAG, "Keep-alive: connection lost, attempting reconnect...")
            scope.launch {
                val settings = configStore.settings.first()
                connectMqtt(settings)
            }
        } else if (mqttConnected && !userDisconnected) {
            if (connectionRef.lastConnectTime > 0) {
                val connectDuration = System.currentTimeMillis() - connectionRef.lastConnectTime
                val timeSinceLastMessage = if (connectionRef.lastMessageTime > 0) {
                    System.currentTimeMillis() - connectionRef.lastMessageTime
                } else {
                    Long.MAX_VALUE
                }
                if (connectDuration >= minConnectStableTime && timeSinceLastMessage >= maxNoMessageTime) {
                    AppLogger.d(TAG, "Keep-alive: reconnecting to refresh callback (connected ${connectDuration / 1000}s, no message for ${timeSinceLastMessage / 1000}s)...")
                    scope.launch {
                        val settings = configStore.settings.first()
                        connectMqtt(settings)
                    }
                } else {
                    AppLogger.d(TAG, "Keep-alive: connection healthy (connected ${connectDuration / 1000}s, last message ${if (connectionRef.lastMessageTime > 0) "${timeSinceLastMessage / 1000}s ago" else "never"}), skip reconnect")
                }
            } else {
                AppLogger.d(TAG, "Keep-alive: connection just established, skip reconnect")
            }
        }

        scheduleKeepAliveAlarm()
    }

    fun tryAutoConnect() {
        if (_connectionState.value != MqttService.ConnectionState.DISCONNECTED) return
        scope.launch {
            configStore.settings.first().let { settings ->
                val isConfigured = settings.brokerUrl != MqttSettings().brokerUrl
                if (settings.autoConnect && isConfigured) {
                    AppLogger.d(TAG, "Auto connecting on startup...")
                    connectMqtt(settings)
                } else if (!isConfigured) {
                    AppLogger.d(TAG, "Skip auto connect: broker not configured")
                }
            }
        }
    }

    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatInterval)
                val state = _connectionState.value.name
                val mqttConnected = mqttClient?.isConnected == true
                AppLogger.d(TAG, "Heartbeat: alive, state=$state, mqtt=$mqttConnected")
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun cancelReconnectJob() {
        reconnectJob?.cancel()
        reconnectAttempt = 0
    }

    fun topicForPublish(topic: String): String {
        var t = topic.trim()
        val hashIndex = t.indexOf('#')
        if (hashIndex >= 0) {
            t = t.substring(0, hashIndex).trim().trimEnd('/')
            if (t.isEmpty()) t = "notice"
        }
        if (t.contains("+")) {
            t = t.split("/").map { if (it == "+") "reply" else it }.joinToString("/")
        }
        return t
    }
}
