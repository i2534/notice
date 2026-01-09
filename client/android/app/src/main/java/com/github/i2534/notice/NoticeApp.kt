package com.github.i2534.notice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.github.i2534.notice.util.AppLogger

class NoticeApp : Application() {

    companion object {
        const val CHANNEL_SERVICE = "mqtt_service"
        const val CHANNEL_MESSAGE = "mqtt_message"
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 服务通知渠道 (低优先级)
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }

        // 消息通知渠道 (高优先级)
        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGE,
            getString(R.string.notification_message_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_message_channel_desc)
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)  // 启用桌面图标角标
        }

        notificationManager.createNotificationChannels(listOf(serviceChannel, messageChannel))
    }
}
