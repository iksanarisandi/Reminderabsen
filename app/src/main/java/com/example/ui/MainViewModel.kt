package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.reminders.AlarmScheduler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AttendanceDatabase.getDatabase(application)
    val repository = AttendanceRepository(database.attendanceDao(), application)

    val scheduleConfig: StateFlow<ScheduleConfig?> = repository.scheduleConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val attendanceHistory: StateFlow<List<AttendanceRecord>> = repository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _todayRecord = MutableStateFlow<AttendanceRecord?>(null)
    val todayRecord: StateFlow<AttendanceRecord?> = _todayRecord.asStateFlow()

    private val _activeReminderDialog = MutableStateFlow<String?>(null) // "CLOCK_IN", "CLOCK_OUT", or null
    val activeReminderDialog: StateFlow<String?> = _activeReminderDialog.asStateFlow()

    init {
        viewModelScope.launch {
            // Clean up past pending entries
            repository.cleanPastPendingRecords()
            // Make sure today's record exists and match configuration
            configFlowOrTodayRecord()
        }
    }

    private fun configFlowOrTodayRecord() {
        viewModelScope.launch {
            scheduleConfig.collect { config ->
                if (config != null) {
                    repository.ensureTodayRecordExists(config)
                    refreshTodayRecord()
                    checkAutomaticDialogTrigger(config)
                }
            }
        }
    }

    suspend fun refreshTodayRecord() {
        _todayRecord.value = repository.getOrCreateTodayRecord()
    }

    fun triggerMissedEvaluation() {
        viewModelScope.launch {
            repository.checkAndMarkMissedSessions()
            refreshTodayRecord()
        }
    }

    private fun checkAutomaticDialogTrigger(config: ScheduleConfig) {
        val now = Calendar.getInstance()
        val nowTime = now.timeInMillis
        val dayOfWeek = repository.getDayOfWeekString(now)
        
        if (!config.isDayActive(dayOfWeek)) return

        viewModelScope.launch {
            val record = repository.getOrCreateTodayRecord()
            
            // Check Clock In
            if (record.clockInStatus == "PENDING") {
                val startIn = calendarWithTime(config.clockInStartHour, config.clockInStartMinute)
                val endIn = calendarWithTime(config.clockInEndHour, config.clockInEndMinute)
                if (nowTime >= startIn.timeInMillis && nowTime <= endIn.timeInMillis) {
                    val snoozed = record.snoozeInUntil != null && record.snoozeInUntil > nowTime
                    if (!snoozed) {
                        _activeReminderDialog.value = "CLOCK_IN"
                        return@launch
                    }
                }
            }

            // Check Clock Out
            if (record.clockOutStatus == "PENDING") {
                val startOut = calendarWithTime(config.clockOutStartHour, config.clockOutStartMinute)
                val endOut = calendarWithTime(config.clockOutEndHour, config.clockOutEndMinute)
                if (nowTime >= startOut.timeInMillis && nowTime <= endOut.timeInMillis) {
                    val snoozed = record.snoozeOutUntil != null && record.snoozeOutUntil > nowTime
                    if (!snoozed) {
                        _activeReminderDialog.value = "CLOCK_OUT"
                        return@launch
                    }
                }
            }
        }
    }

    private fun calendarWithTime(hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    fun showReminderDialog(type: String) {
        _activeReminderDialog.value = type
    }

    fun dismissReminderDialog() {
        _activeReminderDialog.value = null
    }

    fun onClockInDone() {
        viewModelScope.launch {
            repository.markClockInDone()
            refreshTodayRecord()
            dismissReminderDialog()
        }
    }

    fun onClockInSnooze() {
        viewModelScope.launch {
            // snooze with current config interval (e.g. 10m/15m)
            val interval = scheduleConfig.value?.clockInInterval ?: 10
            repository.snoozeClockIn(interval)
            refreshTodayRecord()
            dismissReminderDialog()
        }
    }

    fun onClockOutDone() {
        viewModelScope.launch {
            repository.markClockOutDone()
            refreshTodayRecord()
            dismissReminderDialog()
        }
    }

    fun onClockOutSnooze() {
        viewModelScope.launch {
            val interval = scheduleConfig.value?.clockOutInterval ?: 15
            repository.snoozeClockOut(interval)
            refreshTodayRecord()
            dismissReminderDialog()
        }
    }

    fun saveSchedule(
        activeDays: String,
        clockInS: String, // format "HH:MM"
        clockInE: String,
        clockInInt: Int,
        clockOutS: String,
        clockOutE: String,
        clockOutInt: Int
    ) {
        viewModelScope.launch {
            val inStartParts = clockInS.split(":")
            val inEndParts = clockInE.split(":")
            val outStartParts = clockOutS.split(":")
            val outEndParts = clockOutE.split(":")

            val updatedConfig = ScheduleConfig(
                id = 0,
                onboarded = true,
                activeDays = activeDays,
                clockInStartHour = inStartParts.getOrNull(0)?.toIntOrNull() ?: 8,
                clockInStartMinute = inStartParts.getOrNull(1)?.toIntOrNull() ?: 0,
                clockInEndHour = inEndParts.getOrNull(0)?.toIntOrNull() ?: 9,
                clockInEndMinute = inEndParts.getOrNull(1)?.toIntOrNull() ?: 30,
                clockInInterval = clockInInt,
                clockOutStartHour = outStartParts.getOrNull(0)?.toIntOrNull() ?: 17,
                clockOutStartMinute = outStartParts.getOrNull(1)?.toIntOrNull() ?: 0,
                clockOutEndHour = outEndParts.getOrNull(0)?.toIntOrNull() ?: 18,
                clockOutEndMinute = outEndParts.getOrNull(1)?.toIntOrNull() ?: 30,
                clockOutInterval = clockOutInt
            )

            repository.saveSchedule(updatedConfig)
            refreshTodayRecord()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            database.attendanceDao().clearAllRecords()
            refreshTodayRecord()
        }
    }
}
