package com.github.i2534.notice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.github.i2534.notice.data.MqttConfigStore
import com.github.i2534.notice.service.MqttService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, checking auto-connect setting")

            CoroutineScope(Dispatchers.IO).launch {
                val settings = MqttConfigStore(context).settings.first()
                if (settings.autoConnect) {
                    Log.d(TAG, "Auto-connect enabled, starting MqttService")
                    val serviceIntent = Intent(context, MqttService::class.java)
                    context.startForegroundService(serviceIntent)
                }
            }
        }
    }
}
