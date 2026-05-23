package com.example.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.AttendanceDatabase
import com.example.data.AttendanceRecord
import com.example.data.ScheduleConfig
import java.text.SimpleDateFormat
import java.util.*

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    suspend fun scheduleNextAlarm(context: Context) {
        val database = AttendanceDatabase.getDatabase(context)
        val dao = database.attendanceDao()
        val config = dao.getScheduleConfig() ?: return
        
        val now = Calendar.getInstance()
        val nowTime = now.timeInMillis

        // Exclude done/missed/no_schedule windows
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = sdf.format(now.time)
        val todayRecord = dao.getAttendanceRecord(todayStr)

        val nextTriggerInMs = calculateNextAlarmTime(config, todayRecord, nowTime)
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_TRIGGER_ALARM"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (nextTriggerInMs == null) {
            Log.d(TAG, "No future alarm candidates found. Alarm cancelled.")
            alarmManager.cancel(pendingIntent)
            return
        }

        Log.d(TAG, "Scheduling next alarm for: ${Date(nextTriggerInMs)}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextTriggerInMs,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextTriggerInMs,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextTriggerInMs,
                pendingIntent
            )
        }
    }

    fun calculateNextAlarmTime(
        config: ScheduleConfig,
        todayRecord: AttendanceRecord?,
        nowTime: Long
    ): Long? {
        val candidates = mutableListOf<Long>()

        for (dayOffset in 0..7) {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = nowTime
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }

            val dayOfWeekInt = calendar.get(Calendar.DAY_OF_WEEK)
            val dayName = getDayName(dayOfWeekInt)

            if (!config.isDayActive(dayName)) {
                continue
            }

            // Candidates for Clock-In
            val isClockInDoneToday = dayOffset == 0 && todayRecord != null && todayRecord.clockInStatus != "PENDING"
            if (!isClockInDoneToday) {
                // Generate all normal intervals
                val startInCal = calendar.clone() as Calendar
                startInCal.set(Calendar.HOUR_OF_DAY, config.clockInStartHour)
                startInCal.set(Calendar.MINUTE, config.clockInStartMinute)
                startInCal.set(Calendar.SECOND, 0)
                startInCal.set(Calendar.MILLISECOND, 0)

                val endInCal = calendar.clone() as Calendar
                endInCal.set(Calendar.HOUR_OF_DAY, config.clockInEndHour)
                endInCal.set(Calendar.MINUTE, config.clockInEndMinute)
                endInCal.set(Calendar.SECOND, 0)
                endInCal.set(Calendar.MILLISECOND, 0)

                var currentInCal = startInCal.clone() as Calendar
                while (currentInCal.timeInMillis <= endInCal.timeInMillis) {
                    val triggerTime = currentInCal.timeInMillis
                    if (triggerTime > nowTime) {
                        // Check if it is within snooze
                        if (dayOffset == 0 && todayRecord?.snoozeInUntil != null) {
                            if (triggerTime < todayRecord.snoozeInUntil) {
                                // Skip because we are snoozing past it
                                currentInCal.add(Calendar.MINUTE, config.clockInInterval)
                                continue
                            }
                        }
                        candidates.add(triggerTime)
                    }
                    currentInCal.add(Calendar.MINUTE, config.clockInInterval)
                }

                // Append snooze clock-in if it is active for today
                if (dayOffset == 0 && todayRecord?.snoozeInUntil != null && todayRecord.snoozeInUntil > nowTime) {
                    candidates.add(todayRecord.snoozeInUntil)
                }
            }

            // Candidates for Clock-Out
            val isClockOutDoneToday = dayOffset == 0 && todayRecord != null && todayRecord.clockOutStatus != "PENDING"
            if (!isClockOutDoneToday) {
                // Generate all normal intervals
                val startOutCal = calendar.clone() as Calendar
                startOutCal.set(Calendar.HOUR_OF_DAY, config.clockOutStartHour)
                startOutCal.set(Calendar.MINUTE, config.clockOutStartMinute)
                startOutCal.set(Calendar.SECOND, 0)
                startOutCal.set(Calendar.MILLISECOND, 0)

                val endOutCal = calendar.clone() as Calendar
                endOutCal.set(Calendar.HOUR_OF_DAY, config.clockOutEndHour)
                endOutCal.set(Calendar.MINUTE, config.clockOutEndMinute)
                endOutCal.set(Calendar.SECOND, 0)
                endOutCal.set(Calendar.MILLISECOND, 0)

                var currentOutCal = startOutCal.clone() as Calendar
                while (currentOutCal.timeInMillis <= endOutCal.timeInMillis) {
                    val triggerTime = currentOutCal.timeInMillis
                    if (triggerTime > nowTime) {
                        // Check if it is within snooze
                        if (dayOffset == 0 && todayRecord?.snoozeOutUntil != null) {
                            if (triggerTime < todayRecord.snoozeOutUntil) {
                                // Skip
                                currentOutCal.add(Calendar.MINUTE, config.clockOutInterval)
                                continue
                            }
                        }
                        candidates.add(triggerTime)
                    }
                    currentOutCal.add(Calendar.MINUTE, config.clockOutInterval)
                }

                // Append snooze clock-out if it is active for today
                if (dayOffset == 0 && todayRecord?.snoozeOutUntil != null && todayRecord.snoozeOutUntil > nowTime) {
                    candidates.add(todayRecord.snoozeOutUntil)
                }
            }
        }

        return candidates.minOrNull()
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
