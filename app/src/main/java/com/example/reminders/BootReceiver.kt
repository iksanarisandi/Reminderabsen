package com.example.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Boot completed. Restoring attendance alerts.")
            val pendingResult = goAsync()
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                try {
                    AlarmScheduler.scheduleNextAlarm(context)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error restoring alarm schedule on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
