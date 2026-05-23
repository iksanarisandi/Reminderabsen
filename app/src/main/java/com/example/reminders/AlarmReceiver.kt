package com.example.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AttendanceDatabase
import com.example.data.AttendanceRecord
import com.example.data.ScheduleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("AlarmReceiver", "onReceive action = $action")

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)

        scope.launch {
            try {
                val db = AttendanceDatabase.getDatabase(context)
                val dao = db.attendanceDao()

                // 1. Clean up past days & check missed
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                dao.markPastPendingClockInAsMissed(todayStr)
                dao.markPastPendingClockOutAsMissed(todayStr)

                val config = dao.getScheduleConfig()
                if (config != null) {
                    val todayRecord = dao.getAttendanceRecord(todayStr)
                    
                    if (action == "com.example.ACTION_TRIGGER_ALARM") {
                        evaluateAndShowNotifications(context, config, todayRecord)
                    }
                }

                // 2. Always reschedule next alarm
                AlarmScheduler.scheduleNextAlarm(context)

            } catch (e: Exception) {
                Log.e("AlarmReceiver", "Error processing alarm trigger", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun evaluateAndShowNotifications(
        context: Context,
        config: ScheduleConfig,
        record: AttendanceRecord?
    ) {
        val now = Calendar.getInstance()
        val nowTime = now.timeInMillis
        val dayOfWeekInt = now.get(Calendar.DAY_OF_WEEK)
        val dayName = getDayName(dayOfWeekInt)

        // Make sure today is an active day
        if (!config.isDayActive(dayName)) {
            Log.d("AlarmReceiver", "Today ($dayName) is not a scheduled active day.")
            return
        }

        // 1. Evaluate Clock-In Window
        val isClockInPending = record == null || record.clockInStatus == "PENDING"
        if (isClockInPending) {
            val startInCal = now.clone() as Calendar
            startInCal.set(Calendar.HOUR_OF_DAY, config.clockInStartHour)
            startInCal.set(Calendar.MINUTE, config.clockInStartMinute)
            startInCal.set(Calendar.SECOND, 0)
            startInCal.set(Calendar.MILLISECOND, 0)

            val endInCal = now.clone() as Calendar
            endInCal.set(Calendar.HOUR_OF_DAY, config.clockInEndHour)
            endInCal.set(Calendar.MINUTE, config.clockInEndMinute)
            endInCal.set(Calendar.SECOND, 0)
            endInCal.set(Calendar.MILLISECOND, 0)

            if (nowTime >= startInCal.timeInMillis && nowTime <= endInCal.timeInMillis) {
                // Check snooze
                val isSnoozed = record?.snoozeInUntil != null && record.snoozeInUntil > nowTime
                if (!isSnoozed) {
                    showNotification(
                        context,
                        "Reminder: Clock In",
                        "Remember to register your entry for today!",
                        "CLOCK_IN"
                    )
                }
            }
        }

        // 2. Evaluate Clock-Out Window
        val isClockOutPending = record == null || record.clockOutStatus == "PENDING"
        if (isClockOutPending) {
            val startOutCal = now.clone() as Calendar
            startOutCal.set(Calendar.HOUR_OF_DAY, config.clockOutStartHour)
            startOutCal.set(Calendar.MINUTE, config.clockOutStartMinute)
            startOutCal.set(Calendar.SECOND, 0)
            startOutCal.set(Calendar.MILLISECOND, 0)

            val endOutCal = now.clone() as Calendar
            endOutCal.set(Calendar.HOUR_OF_DAY, config.clockOutEndHour)
            endOutCal.set(Calendar.MINUTE, config.clockOutEndMinute)
            endOutCal.set(Calendar.SECOND, 0)
            endOutCal.set(Calendar.MILLISECOND, 0)

            if (nowTime >= startOutCal.timeInMillis && nowTime <= endOutCal.timeInMillis) {
                // Check snooze
                val isSnoozed = record?.snoozeOutUntil != null && record.snoozeOutUntil > nowTime
                if (!isSnoozed) {
                    showNotification(
                        context,
                        "Reminder: Clock Out",
                        "Time to clock out! Remember to log your attendance.",
                        "CLOCK_OUT"
                    )
                }
            }
        }
    }

    private fun showNotification(context: Context, title: String, text: String, type: String) {
        val channelId = "clock_reminder_notifications"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Attendance Reminders"
            val desc = "Alerts to remind you to clock in/out"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = desc
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to launch MainActivity with type argument to open corresponding dialog
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_REMINDER_TYPE", type)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            if (type == "CLOCK_IN") 2001 else 2002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Full-screen intent to launch popup over all apps
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_REMINDER_TYPE", type)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            if (type == "CLOCK_IN") 3001 else 3002,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(false)

        notificationManager.notify(if (type == "CLOCK_IN") 1 else 2, builder.build())
    }

    private fun getDayName(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            else -> ""
        }
    }
}
