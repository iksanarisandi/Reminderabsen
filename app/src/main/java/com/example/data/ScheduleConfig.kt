package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedule_config")
data class ScheduleConfig(
    @PrimaryKey val id: Int = 0,
    val onboarded: Boolean = false,
    val activeDays: String = "Monday,Tuesday,Wednesday,Thursday,Friday", // comma separated
    val clockInStartHour: Int = 6,
    val clockInStartMinute: Int = 0,
    val clockInEndHour: Int = 14,
    val clockInEndMinute: Int = 0,
    val clockInInterval: Int = 5, // in minutes
    val clockOutStartHour: Int = 16,
    val clockOutStartMinute: Int = 0,
    val clockOutEndHour: Int = 23,
    val clockOutEndMinute: Int = 0,
    val clockOutInterval: Int = 5 // in minutes
) {
    val activeDaysList: List<String>
        get() = if (activeDays.isBlank()) emptyList() else activeDays.split(",")

    fun isDayActive(dayName: String): Boolean {
        return activeDaysList.any { it.trim().equals(dayName, ignoreCase = true) }
    }
}
