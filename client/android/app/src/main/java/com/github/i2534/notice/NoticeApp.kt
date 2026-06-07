package com.github.i2534.notice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.github.i2534.notice.R
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.util.AppLogger
import com.github.i2534.notice.util.MessageBanner
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import com.github.i2534.notice.ui.CopyLinkPlugin
import com.github.i2534.notice.ui.SoftBreakPlugin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicReference

class NoticeApp : Application() {

    private val markwonRef = AtomicReference<Markwon?>()
    @Volatile private var markwonUiMode: Int = -1

    /** 渲染文本块（粗体、链接、表格等）；图片由 MessageContentRenderer 单独用 ImageView 加载。uiMode 变化后自动重建。 */
    val markwon: Markwon
        get() {
            val currentUiMode = resources.configuration.uiMode
            var instance = markwonRef.get()
            if (instance == null || markwonUiMode != currentUiMode) {
                instance = createMarkwon()
                markwonRef.set(instance)
                markwonUiMode = currentUiMode
            }
            return instance
        }

    private fun createMarkwon(): Markwon {
        val density = resources.displayMetrics.density
        val isNight = isAppInNightMode()
        val surfaceVariant = if (isNight) 0xFF1C2128.toInt() else 0xFFE8E8ED.toInt()
        val cellPadding = (8 * density).toInt()
        return Markwon.builder(this)
            .usePlugin(SoftBreakPlugin)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(CopyLinkPlugin)
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

    /** Application.resources 不跟随 AppCompatDelegate，直接查 delegate */
    private fun isAppInNightMode(): Boolean {
        return when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "mqtt_service"
        const val CHANNEL_MESSAGE = "mqtt_message"
    }

    override fun onCreate() {
        super.onCreate()
        // 同步加载主题设置，确保 markwon lazy 创建时主题已生效
        val themeMode = runBlocking(Dispatchers.IO) {
            MqttConfigStore(this@NoticeApp).settings.first().themeMode
        }
        applyThemeMode(themeMode)
        MessageBanner.init(this)
        AppLogger.init(this)
        createNotificationChannels()
    }

    private fun applyThemeMode(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
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
