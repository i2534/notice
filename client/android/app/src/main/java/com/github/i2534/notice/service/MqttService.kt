package com.github.i2534.notice.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.github.i2534.notice.data.AppDatabase
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.data.MqttSettings
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.ui.ContentBlockParser
import com.github.i2534.notice.util.AppLogger
import com.github.i2534.notice.util.MessageHistoryFetcher
import com.github.i2534.notice.util.MessageHistorySync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

class MqttService : Service() {

    companion object {
        private const val TAG = "MqttService"
        const val ACTION_KEEP_ALIVE = "com.github.i2534.notice.KEEP_ALIVE"
        const val ACTION_CLEAR_MESSAGES = "com.github.i2534.notice.CLEAR_MESSAGES"
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }

    private val binder = LocalBinder()

    // ── Coroutine scopes ───────────────────────────────────────────────
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("Mqtt-IO"))

    // ── Config / db ─────────────────────────────────────────────────────
    private val configStore by lazy { MqttConfigStore(this) }
    private val database by lazy { AppDatabase.getInstance(this) }
    private val messageDao by lazy { database.messageDao() }
    private val mediaCacheDao by lazy { database.mediaCacheDao() }

    /** Cached settings, kept in sync via the collect loop in onCreate. */
    private var currentSettings: MqttSettings? = null

    // ── State flows ─────────────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messagesAsc = MutableStateFlow<List<NoticeMessage>>(emptyList())
    val messagesAsc: StateFlow<List<NoticeMessage>> = _messagesAsc.asStateFlow()

    private val _latestMessage = MutableSharedFlow<NoticeMessage>(replay = 1)
    val latestMessage: SharedFlow<NoticeMessage> = _latestMessage.asSharedFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    // ── Delegated managers ──────────────────────────────────────────────
    private val connectionRef = Ref()
    private val messageIdCounter = java.util.concurrent.atomic.AtomicInteger(2000)

    private val connectionManager: MqttConnectionManager by lazy {
        MqttConnectionManager(
            context = this,
            scope = ioScope,
            configStore = configStore,
            _connectionState = _connectionState,
            connectionRef = connectionRef,
            userDisconnected = false,
            onConnected = { onConnected() },
            onMessage = { topic, payload -> messageHandler.handleMessage(topic, payload) }
        )
    }

    private val messageHandler: MessageHandler by lazy {
        MessageHandler(
            context = this,
            scope = ioScope,
            messageDao = messageDao,
            mediaCacheDao = mediaCacheDao,
            _messagesAsc = _messagesAsc,
            _latestMessage = _latestMessage,
            _unreadCount = _unreadCount,
            messageIdCounter = messageIdCounter
        )
    }

    // ── Service lifecycle ───────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        AppLogger.d(TAG, "MqttService created")
        connectionManager.apply {
            startHeartbeat()
            registerDozeReceiver()
            registerNetworkCallback()
            scheduleKeepAliveAlarm()
        }
        loadMessagesAsc()
        // Keep currentSettings in sync — needed by publishReply().
        ioScope.launch {
            configStore.settings.collect { currentSettings = it }
        }
    }

    private fun onConnected() {
        ioScope.launch {
            syncMissedMessages()
        }
    }

    /**
     * 重连后从 HTTP 历史补齐 MQTT 离线/会话过期期间漏掉的消息。
     * 使用近 24h 回看窗口 + 服务端 id 去重（兼容旧 hist-{id} / 规范化指纹）。
     */
    private suspend fun syncMissedMessages() {
        val settings = currentSettings ?: configStore.settings.first()
        val baseUrl = MessageHistorySync.resolveHttpBaseUrl(settings.serverUrl, settings.brokerUrl)
        if (baseUrl.isNullOrBlank()) {
            AppLogger.d(TAG, "Skip history sync: no HTTP base URL (set serverUrl or use ws/wss broker)")
            return
        }
        if (settings.authToken.isBlank()) {
            AppLogger.d(TAG, "Skip history sync: no auth token")
            return
        }

        val lookbackMs = 24 * 60 * 60 * 1000L
        val afterTs = System.currentTimeMillis() - lookbackMs
        val recent = messageDao.getRecentMessagesAsc(500)
            .filter { it.timestamp >= afterTs }
        val localIds = recent.map { it.id }.toSet()
        val fingerprints = recent
            .map { MessageHistorySync.normalizedFingerprint(it.topic, it.content) }
            .toSet()

        val missed = withContext(Dispatchers.IO) {
            MessageHistoryFetcher.fetchMissed(
                baseUrl = baseUrl,
                token = settings.authToken,
                afterTimestampMs = afterTs,
                localIds = localIds,
                localFingerprints = fingerprints
            )
        }
        if (missed.isEmpty()) {
            AppLogger.d(TAG, "History sync: no missed messages")
            return
        }

        // 仅插入本地尚不存在的 id（含 hist-* 兼容）
        val trulyNew = missed.filter { msg ->
            !messageDao.existsById(msg.id) && !messageDao.existsById("hist-${msg.id}")
        }
        if (trulyNew.isEmpty()) {
            AppLogger.d(TAG, "History sync: ${missed.size} candidate(s) already local, skip notify")
            return
        }

        AppLogger.i(TAG, "History sync: inserting ${trulyNew.size} missed message(s)")
        messageDao.insertAll(trulyNew)
        messageDao.trimToSize(500)
        _messagesAsc.update { list ->
            val existingIds = list.map { it.id }.toSet()
            val toAdd = trulyNew.filter { it.id !in existingIds }
            val updated = (list + toAdd).sortedBy { it.timestamp }
            if (updated.size > 500) updated.takeLast(500) else updated
        }
        _unreadCount.update { it + trulyNew.size }
        // 仅对最新一条弹通知，避免补历史时刷屏
        trulyNew.lastOrNull()?.let { messageHandler.showMessageNotification(it) }
        connectionRef.lastMessageTime = System.currentTimeMillis()
    }

    private fun loadMessagesAsc() {
        ioScope.launch {
            _messagesAsc.value = messageDao.getRecentMessagesAsc(500).asReversed()
            AppLogger.d(TAG, "Loaded ${_messagesAsc.value.size} messages")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, messageHandler.createServiceNotification())

        when (intent?.action) {
            ACTION_KEEP_ALIVE -> connectionManager.handleKeepAlive()
            ACTION_CLEAR_MESSAGES -> clearMessages()
            else -> {
                if (_connectionState.value == ConnectionState.DISCONNECTED) {
                    connectionManager.tryAutoConnect()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        connectionManager.unregisterDozeReceiver()
        connectionManager.unregisterNetworkCallback()
        connectionManager.cancelKeepAliveAlarm()
        connectionManager.stopHeartbeat()
        disconnect()
        ioScope.cancel()
        super.onDestroy()
    }

    // ── Public API ──────────────────────────────────────────────────────

    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING) return

        connectionManager.userDisconnected = false
        connectionManager.cancelReconnectJob()

        ioScope.launch {
            configStore.settings.first().let { settings ->
                connectionManager.connectMqtt(settings)
            }
        }
    }

    fun disconnect() {
        connectionManager.userDisconnected = true
        connectionManager.cancelReconnectJob()
        ioScope.launch {
            connectionManager.disconnectMqtt()
            _connectionState.value = ConnectionState.DISCONNECTED
            AppLogger.d(TAG, "Disconnected")
        }
    }

    fun publishReply(content: String, replyToTopic: String? = null): Boolean {
        val topic = replyToTopic?.takeIf { it.isNotBlank() }
            ?.let { connectionManager.topicForPublish(it) }
            ?: return false

        val client = connectionManager.mqttClient ?: return false
        if (!client.isConnected) return false

        messageHandler.lastSentReplyContent = content.trim()
        messageHandler.lastSentReplyTime = System.currentTimeMillis()

        val payload = JSONObject().apply {
            put("title", "回复")
            put("content", content)
            put("timestamp", System.currentTimeMillis())
            put("client", "android")
        }.toString().toByteArray(Charsets.UTF_8)

        ioScope.launch {
            try {
                client.publish(topic, payload, 1, false)
                AppLogger.d(TAG, "Reply published to $topic")
                val msg = NoticeMessage(
                    topic = topic,
                    title = "回复",
                    content = content,
                    timestamp = System.currentTimeMillis(),
                    client = "android",
                    isOutgoing = true
                )
                messageDao.insert(msg)
                _messagesAsc.update { list ->
                    val updated = list + msg
                    if (updated.size > 500) updated.takeLast(500) else updated
                }
                messageDao.trimToSize(500)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Publish reply failed: ${e.message}")
                messageHandler.lastSentReplyContent = null
            }
        }
        return true
    }

    fun refreshSettings() {
        ioScope.launch {
            configStore.settings.first().let { currentSettings = it }
        }
    }

    fun clearMessages() {
        messageHandler.clearUnreadNotifications()
        ioScope.launch {
            val paths = mediaCacheDao.getAllLocalPaths()
            paths.forEach { java.io.File(it).delete() }
            mediaCacheDao.deleteAll()
            messageDao.deleteAll()
            _messagesAsc.value = emptyList()
        }
    }

    fun deleteMessage(messageId: String) {
        ioScope.launch {
            messageDao.delete(messageId)
            _messagesAsc.update { it.filter { m -> m.id != messageId } }
        }
    }

    fun deleteMessages(messageIds: Set<String>) {
        ioScope.launch {
            val messages = messageDao.getByIds(messageIds.toList())
            val urls = messages.flatMap { ContentBlockParser.extractMediaUrls(it.content) }.distinct()
            if (urls.isNotEmpty()) {
                val entities = mediaCacheDao.getByUrls(urls)
                entities.forEach { java.io.File(it.localPath).delete() }
                mediaCacheDao.deleteByUrls(urls)
            }
            messageDao.deleteByIds(messageIds.toList())
            _messagesAsc.update { it.filter { m -> m.id !in messageIds } }
        }
    }

    fun clearUnreadCount() {
        messageHandler.clearUnreadNotifications()
        startForeground(1001, messageHandler.createServiceNotification())
    }
}
