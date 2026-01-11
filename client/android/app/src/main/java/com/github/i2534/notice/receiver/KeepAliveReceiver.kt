package com.github.i2534.notice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        const val ACTION_KEEP_ALIVE = "com.github.i2534.notice.KEEP_ALIVE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_KEEP_ALIVE) {
            AppLogger.d(TAG, "Keep-alive alarm triggered, checking MQTT connection...")
            
            // 发送 Intent 让 MqttService 检查连接状态
            val serviceIntent = Intent(context, MqttService::class.java).apply {
                action = MqttService.ACTION_KEEP_ALIVE
            }
            context.startService(serviceIntent)
        }
    }
}
