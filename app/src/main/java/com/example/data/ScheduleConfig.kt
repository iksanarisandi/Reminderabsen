package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedule_config")
data class ScheduleConfig(
    @PrimaryKey val id: Int = 0,
    val onboarded: Boolean = false,
    val activeDays: String = "Monday,Tuesday,Wednesday,Thursday,Friday", // comma separated
    val clockInStartHour: Int = 8,
    val clockInStartMinute: Int = 0,
    val clockInEndHour: Int = 9,
    val clockInEndMinute: Int = 30,
    val clockInInterval: Int = 15, // in minutes
    val clockOutStartHour: Int = 17,
    val clockOutStartMinute: Int = 0,
    val clockOutEndHour: Int = 18,
    val clockOutEndMinute: Int = 30,
    val clockOutInterval: Int = 15 // in minutes
) {
    val activeDaysList: List<String>
        get() = if (activeDays.isBlank()) emptyList() else activeDays.split(",")

    fun isDayActive(dayName: String): Boolean {
        return activeDaysList.any { it.trim().equals(dayName, ignoreCase = true) }
    }
}
