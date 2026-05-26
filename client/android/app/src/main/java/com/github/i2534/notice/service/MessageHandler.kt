package com.github.i2534.notice.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.i2534.notice.NoticeApp
import com.github.i2534.notice.R
import com.github.i2534.notice.data.MediaCacheDao
import com.github.i2534.notice.data.MessageDao
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.ui.MainActivity
import com.github.i2534.notice.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "MessageHandler"

class MessageHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val messageDao: MessageDao,
    private val mediaCacheDao: MediaCacheDao,
    private val _messagesAsc: MutableStateFlow<List<NoticeMessage>>,
    private val _latestMessage: MutableSharedFlow<NoticeMessage>,
    private val _unreadCount: MutableStateFlow<Int>,
    val messageIdCounter: AtomicInteger = AtomicInteger(2000),
    val messageInsertCount: MutableInt = MutableInt(0)
) {

     @Volatile
    var lastSentReplyContent: String? = null

    @Volatile
    var lastSentReplyTime: Long = 0

     fun handleMessage(topic: String, payload: ByteArray) {
        val message = NoticeMessage.parse(topic, payload)

        if (message.content == "__auth_check__") {
            AppLogger.d(TAG, "Ignoring auth check message")
            return
        }

        val sent = lastSentReplyContent
        if (sent != null && message.title == "回复" && message.content == sent &&
            (System.currentTimeMillis() - lastSentReplyTime) < 5000) {
            lastSentReplyContent = null
            AppLogger.d(TAG, "Ignoring duplicate reply from MQTT")
            return
        }

        AppLogger.d(TAG, "Message received: ${message.title}")

        scope.launch {
            messageDao.insert(message)
            _messagesAsc.update { list ->
                val updated = list + message
                if (updated.size > 500) updated.takeLast(500) else updated
            }
            if (messageInsertCount.incrementAndCheck()) {
                messageDao.trimToSize(500)
            }
        }

        _unreadCount.update { it + 1 }

        scope.launch {
            _latestMessage.emit(message)
        }

        showMessageNotification(message)
    }

    fun showMessageNotification(message: NoticeMessage) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val unreadNum = _unreadCount.value

        val notification = NotificationCompat.Builder(context, NoticeApp.CHANNEL_MESSAGE)
            .setContentTitle(message.title)
            .setContentText(message.content)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.content))
            .setNumber(unreadNum)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            applyMiuiBadge(notification, unreadNum)
            NotificationManagerCompat.from(context)
                .notify(messageIdCounter.incrementAndGet(), notification)
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "No notification permission")
        }
    }

    @Suppress("DEPRECATION")
    private fun applyMiuiBadge(notification: Notification, count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        try {
            val field = notification.javaClass.getDeclaredField("extraNotification")
            field.isAccessible = true
            val extraNotification = field.get(notification)
            if (extraNotification != null) {
                val method = extraNotification.javaClass
                    .getDeclaredMethod("setMessageCount", Int::class.javaPrimitiveType)
                method.invoke(extraNotification, count)
            }
        } catch (_: Exception) {
        }
    }

    fun createServiceNotification(): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NoticeApp.CHANNEL_SERVICE)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun clearUnreadNotifications() {
        _unreadCount.value = 0
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun clearMessages() {
        clearUnreadNotifications()
        scope.launch {
            val paths = mediaCacheDao.getAllLocalPaths()
            paths.forEach { path -> java.io.File(path).delete() }
            mediaCacheDao.deleteAll()
            messageDao.deleteAll()
            _messagesAsc.value = emptyList()
        }
    }

    fun deleteMessage(messageId: String) {
        scope.launch {
            messageDao.delete(messageId)
            _messagesAsc.update { it.filter { m -> m.id != messageId } }
        }
    }

}

data class MutableInt(var value: Int = 0) {
    /** Returns true when trim should be triggered (every 50th insert). */
    fun incrementAndCheck(): Boolean {
        value++
        return value % 50 == 0
    }
}
