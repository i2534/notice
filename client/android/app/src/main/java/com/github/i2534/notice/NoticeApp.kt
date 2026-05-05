package com.github.i2534.notice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.ContextCompat
import com.github.i2534.notice.R
import com.github.i2534.notice.util.AppLogger
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import com.github.i2534.notice.ui.SoftBreakPlugin

class NoticeApp : Application() {

    /** 仅用于渲染文本块（粗体、链接、表格等），图片由 MessageContentRenderer 单独用 ImageView 加载。 */
    val markwon: Markwon by lazy {
        val density = resources.displayMetrics.density
        val surfaceVariant = ContextCompat.getColor(this, R.color.surface_variant)
        val cellPadding = (8 * density).toInt()
        Markwon.builder(this)
            .usePlugin(SoftBreakPlugin)
            .usePlugin(TablePlugin.create { builder ->
                builder
                    .tableBorderColor(surfaceVariant)
                    .tableBorderWidth(density.toInt().coerceAtLeast(1))
                    .tableHeaderRowBackgroundColor(surfaceVariant)
                    .tableCellPadding(cellPadding)
                    .build()
            })
            .build()
    }

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
