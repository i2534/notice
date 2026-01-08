package com.github.i2534.notice.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import com.github.i2534.notice.NoticeApp
import com.github.i2534.notice.R
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.MqttSettings
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

class MqttService : Service() {

    companion object {
        private const val TAG = "MqttService"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CONNECT = "com.github.i2534.notice.CONNECT"
        private const val ACTION_DISCONNECT = "com.github.i2534.notice.DISCONNECT"
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mqttClient: MqttAsyncClient? = null
    private var currentSettings: MqttSettings? = null
    private val configStore by lazy { MqttConfigStore(this) }

    private val messageIdCounter = AtomicInteger(2000)

    // 重连控制
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var userDisconnected = false
    private val maxReconnectDelay = 60_000L  // 最大重连间隔 60 秒
    private val baseReconnectDelay = 3_000L  // 基础重连间隔 3 秒

    // 状态流
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<List<NoticeMessage>>(emptyList())
    val messages: StateFlow<List<NoticeMessage>> = _messages.asStateFlow()

    private val _latestMessage = MutableSharedFlow<NoticeMessage>(replay = 0)
    val latestMessage: SharedFlow<NoticeMessage> = _latestMessage.asSharedFlow()

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MqttService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
        }

        startForeground(NOTIFICATION_ID, createServiceNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING) return

        userDisconnected = false
        reconnectJob?.cancel()
        
        scope.launch {
            configStore.settings.first().let { settings ->
                currentSettings = settings
                connectMqtt(settings)
            }
        }
    }

    private fun scheduleReconnect() {
        if (userDisconnected) {
            Log.d(TAG, "User disconnected, skip reconnect")
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // 指数退避：3s, 6s, 12s, 24s, 48s, 60s (max)
            val delay = minOf(baseReconnectDelay * (1L shl reconnectAttempt), maxReconnectDelay)
            reconnectAttempt++
            
            Log.d(TAG, "Reconnecting in ${delay/1000}s (attempt $reconnectAttempt)")
            delay(delay)
            
            if (_connectionState.value == ConnectionState.DISCONNECTED && !userDisconnected) {
                currentSettings?.let { connectMqtt(it) }
            }
        }
    }

    private suspend fun connectMqtt(settings: MqttSettings) {
        _connectionState.value = ConnectionState.CONNECTING
        Log.d(TAG, "Connecting to ${settings.brokerUrl}")

        try {
            // 断开旧连接
            mqttClient?.let {
                if (it.isConnected) {
                    it.disconnect()
                }
                it.close()
            }

            // 获取或生成 Client ID，并持久化
            var clientId = settings.getEffectiveClientId(generateNew = false)
            if (clientId.isBlank()) {
                clientId = settings.getEffectiveClientId(generateNew = true)
                // 保存生成的 clientId
                configStore.save(settings.copy(clientId = clientId))
                Log.d(TAG, "Generated and saved new clientId: $clientId")
            }

            // 创建新客户端
            mqttClient = MqttAsyncClient(
                settings.brokerUrl,
                clientId,
                MemoryPersistence()
            )

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost: ${cause?.message}")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != null && message != null) {
                        handleMessage(topic, message.payload)
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

                // Token 认证 (使用 username 传递 token)
                if (settings.hasAuth()) {
                    userName = settings.authToken
                    Log.d(TAG, "Using token authentication")
                }
            }

            // 连接
            mqttClient?.connect(options)?.waitForCompletion(30000)

            // 订阅
            mqttClient?.subscribe(settings.topic, 1)?.waitForCompletion(10000)

            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempt = 0  // 连接成功，重置重连计数
            Log.d(TAG, "Connected and subscribed to ${settings.topic}")

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()  // 连接失败，安排重连
        }
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        reconnectAttempt = 0
        
        scope.launch {
            try {
                mqttClient?.let {
                    if (it.isConnected) {
                        it.disconnect()?.waitForCompletion(5000)
                    }
                    it.close()
                }
                mqttClient = null
                _connectionState.value = ConnectionState.DISCONNECTED
                Log.d(TAG, "Disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error: ${e.message}")
            }
        }
    }

    private fun handleMessage(topic: String, payload: ByteArray) {
        val message = NoticeMessage.parse(topic, payload)
        Log.d(TAG, "Message received: ${message.title}")

        // 更新消息列表
        _messages.update { current ->
            (listOf(message) + current).take(100) // 保留最近100条
        }

        // 发送到流
        scope.launch {
            _latestMessage.emit(message)
        }

        // 显示通知
        showMessageNotification(message)
    }

    private fun createServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NoticeApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showMessageNotification(message: NoticeMessage) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NoticeApp.CHANNEL_MESSAGE)
            .setContentTitle(message.title)
            .setContentText(message.content)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.content))
            .build()

        try {
            NotificationManagerCompat.from(this)
                .notify(messageIdCounter.incrementAndGet(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission")
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }
}
