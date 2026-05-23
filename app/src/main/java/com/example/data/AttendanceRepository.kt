package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.*

class AttendanceRepository(
    private val attendanceDao: AttendanceDao,
    private val context: Context
) {
    val scheduleConfig: Flow<ScheduleConfig?> = attendanceDao.getScheduleConfigFlow()
    val allHistory: Flow<List<AttendanceRecord>> = attendanceDao.getAllAttendanceRecordsFlow()

    fun getTodayDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date())
    }

    fun getDayOfWeekString(calendar: Calendar = Calendar.getInstance()): String {
        return calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US) ?: ""
    }

    suspend fun getSchedule(): ScheduleConfig? {
        return attendanceDao.getScheduleConfig()
    }

    suspend fun saveSchedule(config: ScheduleConfig) {
        attendanceDao.insertScheduleConfig(config)
        // Clean history records based on new schedule days
        ensureTodayRecordExists(config)
        // Whenever schedule is saved, we reschedule alarms
        rescheduleAlarms()
    }

    suspend fun getOrCreateTodayRecord(): AttendanceRecord {
        val todayStr = getTodayDateString()
        var record = attendanceDao.getAttendanceRecord(todayStr)
        if (record == null) {
            val config = getSchedule()
            val dayOfWeek = getDayOfWeekString()
            val isActive = config?.isDayActive(dayOfWeek) == true
            
            record = AttendanceRecord(
                date = todayStr,
                clockInStatus = if (isActive) "PENDING" else "NO_SCHEDULE",
                clockOutStatus = if (isActive) "PENDING" else "NO_SCHEDULE"
            )
            attendanceDao.insertAttendanceRecord(record)
        }
        return record
    }

    suspend fun ensureTodayRecordExists(config: ScheduleConfig) {
        val todayStr = getTodayDateString()
        val record = attendanceDao.getAttendanceRecord(todayStr)
        val dayOfWeek = getDayOfWeekString()
        val isActive = config.isDayActive(dayOfWeek)
        
        if (record == null) {
            val newRecord = AttendanceRecord(
                date = todayStr,
                clockInStatus = if (isActive) "PENDING" else "NO_SCHEDULE",
                clockOutStatus = if (isActive) "PENDING" else "NO_SCHEDULE"
            )
            attendanceDao.insertAttendanceRecord(newRecord)
        } else {
            // Update statuses to match current config if they were PENDING or NO_SCHEDULE
            var updated = record
            if (record.clockInStatus == "PENDING" || record.clockInStatus == "NO_SCHEDULE") {
                updated = updated.copy(clockInStatus = if (isActive) "PENDING" else "NO_SCHEDULE")
            }
            if (record.clockOutStatus == "PENDING" || record.clockOutStatus == "NO_SCHEDULE") {
                updated = updated.copy(clockOutStatus = if (isActive) "PENDING" else "NO_SCHEDULE")
            }
            if (updated != record) {
                attendanceDao.insertAttendanceRecord(updated)
            }
        }
    }

    suspend fun markClockInDone() {
        val record = getOrCreateTodayRecord()
        val updated = record.copy(
            clockInStatus = "DONE",
            clockInTime = System.currentTimeMillis(),
            snoozeInUntil = null
        )
        attendanceDao.insertAttendanceRecord(updated)
        rescheduleAlarms()
    }

    suspend fun markClockInMissed() {
        val record = getOrCreateTodayRecord()
        val updated = record.copy(
            clockInStatus = "MISSED",
            snoozeInUntil = null
        )
        attendanceDao.insertAttendanceRecord(updated)
        rescheduleAlarms()
    }

    suspend fun snoozeClockIn(minutes: Int) {
        val record = getOrCreateTodayRecord()
        val updated = record.copy(
            snoozeInUntil = System.currentTimeMillis() + (minutes * 60 * 1000)
        )
        attendanceDao.insertAttendanceRecord(updated)
        rescheduleAlarms()
    }

    suspend fun markClockOutDone() {
        val record = getOrCreateTodayRecord()
        val updated = record.copy(
            clockOutStatus = "DONE",
            clockOutTime = System.currentTimeMillis(),
            snoozeOutUntil = null
        )
        attendanceDao.insertAttendanceRecord(updated)
        rescheduleAlarms()
    }

    suspend fun markClockOutMissed() {
        val record = getOrCreateTodayRecord()
        val updated = record.copy(
            clockOutStatus = "MISSED",
            snoozeOutUntil = null
        )
        attendanceDao.insertAttendanceRecord(updated)
        rescheduleAlarms()
    }

    suspend fun snoozeClockOut(minutes: Int) {
        val record = getOrCreateTodayRecord()
        val updated = record.copy(
            snoozeOutUntil = System.currentTimeMillis() + (minutes * 60 * 1000)
        )
        attendanceDao.insertAttendanceRecord(updated)
        rescheduleAlarms()
    }

    /**
     * Cleans up expired past records that are still marked as "PENDING" to be "MISSED".
     */
    suspend fun cleanPastPendingRecords() {
        val todayStr = getTodayDateString()
        attendanceDao.markPastPendingClockInAsMissed(todayStr)
        attendanceDao.markPastPendingClockOutAsMissed(todayStr)
    }

    /**
     * Checks today's session status and marks as missed if the windows have closed.
     */
    suspend fun checkAndMarkMissedSessions() {
        val config = getSchedule() ?: return
        val todayStr = getTodayDateString()
        val record = getOrCreateTodayRecord()
        val now = Calendar.getInstance()
        
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)
        
        val endInHour = config.clockInEndHour
        val endInMinute = config.clockInEndMinute
        val endOutHour = config.clockOutEndHour
        val endOutMinute = config.clockOutEndMinute
        
        var updated = record
        
        // Check if we passed clock-in end time today
        if (record.clockInStatus == "PENDING") {
            if (currentHour > endInHour || (currentHour == endInHour && currentMinute >= endInMinute)) {
                updated = updated.copy(clockInStatus = "MISSED")
                Log.d("AttendanceRepository", "Marked today's Clock-in as MISSED")
            }
        }
        
        // Check if we passed clock-out end time today
        if (record.clockOutStatus == "PENDING") {
            if (currentHour > endOutHour || (currentHour == endOutHour && currentMinute >= endOutMinute)) {
                updated = updated.copy(clockOutStatus = "MISSED")
                Log.d("AttendanceRepository", "Marked today's Clock-out as MISSED")
            }
        }
        
        if (updated != record) {
            attendanceDao.insertAttendanceRecord(updated)
            rescheduleAlarms()
        }
    }

    fun rescheduleAlarms() {
        val intent = android.content.Intent(context, com.example.reminders.AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_RESCHEDULE_ALARMS"
        }
        context.sendBroadcast(intent)
    }
}
