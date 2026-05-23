package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "attendance_records")
data class AttendanceRecord(
    @PrimaryKey val date: String, // format: YYYY-MM-DD
    val clockInStatus: String = "PENDING", // DONE, MISSED, NO_SCHEDULE, PENDING
    val clockInTime: Long? = null, // timestamp is saved when DONE
    val clockOutStatus: String = "PENDING", // DONE, MISSED, NO_SCHEDULE, PENDING
    val clockOutTime: Long? = null,
    val snoozeInUntil: Long? = null, // Time to snooze clock-in reminder
    val snoozeOutUntil: Long? = null // Time to snooze clock-out reminder
)
