package com.github.i2534.notice.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.github.i2534.notice.util.AppLogger
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import com.github.i2534.notice.NoticeApp
import com.github.i2534.notice.R
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.github.i2534.notice.data.AppDatabase
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.MqttSettings
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.receiver.KeepAliveReceiver
import com.github.i2534.notice.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

class MqttService : Service() {

    companion object {
        private const val TAG = "MqttService"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CONNECT = "com.github.i2534.notice.CONNECT"
        private const val ACTION_DISCONNECT = "com.github.i2534.notice.DISCONNECT"
        const val ACTION_KEEP_ALIVE = "com.github.i2534.notice.KEEP_ALIVE"
        
        // Doze 保活闹钟间隔（10 分钟）
        private const val KEEP_ALIVE_INTERVAL = 10 * 60 * 1000L
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    private var mqttClient: MqttAsyncClient? = null
    private var currentSettings: MqttSettings? = null
    private val configStore by lazy { MqttConfigStore(this) }
    private val database by lazy { AppDatabase.getInstance(this) }
    private val messageDao by lazy { database.messageDao() }

    private val messageIdCounter = AtomicInteger(2000)

    // 重连控制
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var userDisconnected = false
    private val maxReconnectDelay = 60_000L  // 最大重连间隔 60 秒
    private val baseReconnectDelay = 3_000L  // 基础重连间隔 3 秒

    // 心跳日志
    private var heartbeatJob: Job? = null
    private val heartbeatInterval = 10_000L  // 10 秒

    // Doze 保活闹钟
    private val alarmManager by lazy { getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val keepAlivePendingIntent by lazy {
        val intent = Intent(this, KeepAliveReceiver::class.java).apply {
            action = KeepAliveReceiver.ACTION_KEEP_ALIVE
        }
        PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Doze 模式监听
    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                val isDozeMode = powerManager.isDeviceIdleMode
                if (isDozeMode) {
                    AppLogger.w(TAG, "Device entered Doze mode")
                } else {
                    AppLogger.i(TAG, "Device exited Doze mode, checking connection...")
                    // 退出 Doze 时检查连接状态
                    handleKeepAlive()
                }
            }
        }
    }

    // 状态流
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 分页消息流
    val messagesPaging: Flow<PagingData<NoticeMessage>> by lazy {
        Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false,
                initialLoadSize = 40
            ),
            pagingSourceFactory = { messageDao.getMessagesPaging() }
        ).flow.cachedIn(scope)
    }

    private val _latestMessage = MutableSharedFlow<NoticeMessage>(replay = 1)
    val latestMessage: SharedFlow<NoticeMessage> = _latestMessage.asSharedFlow()

    // 未读消息计数
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        AppLogger.d(TAG, "MqttService created")
        acquireWakeLock()
        startHeartbeat()
        loadLatestMessage()
        scheduleKeepAliveAlarm()
        registerDozeReceiver()
    }

    private fun loadLatestMessage() {
        scope.launch {
            messageDao.getLatestMessage()?.let { message ->
                _latestMessage.emit(message)
                AppLogger.d(TAG, "Loaded latest message: ${message.title}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createServiceNotification())

        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            ACTION_KEEP_ALIVE -> handleKeepAlive()
            else -> {
                // 首次启动时检查是否需要自动连接
                tryAutoConnect()
            }
        }

        return START_STICKY
    }

    /**
     * 尝试自动连接
     * 条件：autoConnect 为 true 且 broker URL 已被用户设置过（非默认值）
     */
    private fun tryAutoConnect() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        scope.launch {
            configStore.settings.first().let { settings ->
                val isConfigured = settings.brokerUrl != MqttSettings().brokerUrl
                if (settings.autoConnect && isConfigured) {
                    AppLogger.d(TAG, "Auto connecting on startup...")
                    currentSettings = settings
                    connectMqtt(settings)
                } else if (!isConfigured) {
                    AppLogger.d(TAG, "Skip auto connect: broker not configured")
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterDozeReceiver()
        cancelKeepAliveAlarm()
        stopHeartbeat()
        disconnect()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Notice:MqttService"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLogger.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                AppLogger.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
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
            AppLogger.d(TAG, "User disconnected, skip reconnect")
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // 指数退避：3s, 6s, 12s, 24s, 48s, 60s (max)
            val delay = minOf(baseReconnectDelay * (1L shl reconnectAttempt), maxReconnectDelay)
            reconnectAttempt++
            
            AppLogger.d(TAG, "Reconnecting in ${delay/1000}s (attempt $reconnectAttempt)")
            delay(delay)
            
            if (_connectionState.value == ConnectionState.DISCONNECTED && !userDisconnected) {
                currentSettings?.let { connectMqtt(it) }
            }
        }
    }

    private suspend fun connectMqtt(settings: MqttSettings) {
        _connectionState.value = ConnectionState.CONNECTING
        AppLogger.d(TAG, "Connecting to ${settings.brokerUrl}")

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
                AppLogger.d(TAG, "Generated and saved new clientId: $clientId")
            }

            // 创建新客户端
            mqttClient = MqttAsyncClient(
                settings.brokerUrl,
                clientId,
                MemoryPersistence()
            )

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    AppLogger.w(TAG, "Connection lost: ${cause?.message}")
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
                    AppLogger.d(TAG, "Using token authentication")
                }
            }

            // 连接
            mqttClient?.connect(options)?.waitForCompletion(30000)

            // 订阅
            mqttClient?.subscribe(settings.topic, 1)?.waitForCompletion(10000)

            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempt = 0  // 连接成功，重置重连计数
            AppLogger.d(TAG, "Connected and subscribed to ${settings.topic}")

        } catch (e: Exception) {
            AppLogger.e(TAG, "Connection failed: ${e.message}", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()  // 连接失败，安排重连
        }
    }

    private fun startHeartbeat() {
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

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 设置 Doze 保活闹钟
     * 使用 setExactAndAllowWhileIdle 在 Doze 模式下也能触发
     */
    private fun scheduleKeepAliveAlarm() {
        val triggerTime = SystemClock.elapsedRealtime() + KEEP_ALIVE_INTERVAL
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 需要检查权限
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        keepAlivePendingIntent
                    )
                    AppLogger.d(TAG, "Keep-alive alarm scheduled for ${KEEP_ALIVE_INTERVAL / 60000} minutes")
                } else {
                    // 没有精确闹钟权限，使用非精确闹钟
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
                AppLogger.d(TAG, "Keep-alive alarm scheduled for ${KEEP_ALIVE_INTERVAL / 60000} minutes")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to schedule keep-alive alarm: ${e.message}")
        }
    }

    /**
     * 取消 Doze 保活闹钟
     */
    private fun cancelKeepAliveAlarm() {
        alarmManager.cancel(keepAlivePendingIntent)
        AppLogger.d(TAG, "Keep-alive alarm cancelled")
    }

    /**
     * 注册 Doze 模式变化监听
     */
    private fun registerDozeReceiver() {
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dozeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dozeReceiver, filter)
        }
        AppLogger.d(TAG, "Doze mode receiver registered")
    }

    /**
     * 取消注册 Doze 模式变化监听
     */
    private fun unregisterDozeReceiver() {
        try {
            unregisterReceiver(dozeReceiver)
            AppLogger.d(TAG, "Doze mode receiver unregistered")
        } catch (e: Exception) {
            // 忽略未注册的情况
        }
    }

    /**
     * 处理保活闹钟唤醒
     * 检查连接状态，必要时重连，并重新设置下一次闹钟
     */
    private fun handleKeepAlive() {
        val mqttConnected = mqttClient?.isConnected == true
        val state = _connectionState.value
        
        AppLogger.d(TAG, "Keep-alive check: state=$state, mqtt=$mqttConnected")
        
        // 如果未连接且用户没有主动断开，尝试重连
        if (!mqttConnected && !userDisconnected) {
            AppLogger.d(TAG, "Keep-alive: connection lost, attempting reconnect...")
            connect()
        }
        
        // 重新设置下一次闹钟
        scheduleKeepAliveAlarm()
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
                AppLogger.d(TAG, "Disconnected")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Disconnect error: ${e.message}")
            }
        }
    }

    private fun handleMessage(topic: String, payload: ByteArray) {
        val message = NoticeMessage.parse(topic, payload)

        // 过滤 Web 界面的认证检查消息
        if (message.content == "__auth_check__") {
            AppLogger.d(TAG, "Ignoring auth check message")
            return
        }

        AppLogger.d(TAG, "Message received: ${message.title}")

        // 保存到数据库
        scope.launch {
            messageDao.insert(message)
            // 保留最近 500 条消息
            messageDao.trimToSize(500)
        }

        // 增加未读计数
        _unreadCount.update { it + 1 }

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
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val unreadNum = _unreadCount.value
        
        val notification = NotificationCompat.Builder(this, NoticeApp.CHANNEL_MESSAGE)
            .setContentTitle(message.title)
            .setContentText(message.content)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.content))
            .setNumber(unreadNum)  // 设置桌面图标角标数量
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            // 悬浮通知设置（几秒后自动消失）
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            // 小米手机角标支持
            applyMiuiBadge(notification, unreadNum)
            
            NotificationManagerCompat.from(this)
                .notify(messageIdCounter.incrementAndGet(), notification)
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "No notification permission")
        }
    }

    private fun applyMiuiBadge(notification: android.app.Notification, count: Int) {
        try {
            val field = notification.javaClass.getDeclaredField("extraNotification")
            field.isAccessible = true
            val extraNotification = field.get(notification)
            if (extraNotification != null) {
                val method = extraNotification.javaClass.getDeclaredMethod("setMessageCount", Int::class.javaPrimitiveType)
                method.invoke(extraNotification, count)
            }
        } catch (_: Exception) {
            // 非小米手机或不支持，静默忽略
        }
    }

    fun clearMessages() {
        clearUnreadCount()
        scope.launch { messageDao.deleteAll() }
    }

    fun deleteMessage(messageId: String) {
        scope.launch { messageDao.delete(messageId) }
    }

    fun deleteMessages(messageIds: Set<String>) {
        scope.launch { messageDao.deleteByIds(messageIds.toList()) }
    }

    fun clearUnreadCount() {
        _unreadCount.value = 0
        // 清除所有消息通知以重置桌面角标
        NotificationManagerCompat.from(this).cancelAll()
        // 重新显示服务通知
        startForeground(NOTIFICATION_ID, createServiceNotification())
    }
}
