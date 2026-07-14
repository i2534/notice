package com.github.i2534.notice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.github.i2534.notice.service.MqttService
import com.github.i2534.notice.util.AppLogger

/**
 * Doze 模式保活闹钟接收器
 * 使用 AlarmManager.setExactAndAllowWhileIdle() 在 Doze 模式下定期唤醒
 * 检查 MQTT 连接状态并重连
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeepAliveReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == MqttService.ACTION_KEEP_ALIVE) {
            AppLogger.d(TAG, "Keep-alive alarm triggered, checking MQTT connection...")

            val serviceIntent = Intent(context, MqttService::class.java).apply {
                action = MqttService.ACTION_KEEP_ALIVE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    @Suppress("DEPRECATION")
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start MqttService from keep-alive: ${e.message}")
            }
        }
    }
}
