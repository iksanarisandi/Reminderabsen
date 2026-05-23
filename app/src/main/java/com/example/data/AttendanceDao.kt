package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {

    // Config queries
    @Query("SELECT * FROM schedule_config WHERE id = 0 LIMIT 1")
    fun getScheduleConfigFlow(): Flow<ScheduleConfig?>

    @Query("SELECT * FROM schedule_config WHERE id = 0 LIMIT 1")
    suspend fun getScheduleConfig(): ScheduleConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScheduleConfig(config: ScheduleConfig)

    // Record queries
    @Query("SELECT * FROM attendance_records ORDER BY date DESC")
    fun getAllAttendanceRecordsFlow(): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance_records WHERE date = :date LIMIT 1")
    suspend fun getAttendanceRecord(date: String): AttendanceRecord?

    @Query("UPDATE attendance_records SET clockInStatus = 'MISSED' WHERE date < :todayDate AND clockInStatus = 'PENDING'")
    suspend fun markPastPendingClockInAsMissed(todayDate: String)

    @Query("UPDATE attendance_records SET clockOutStatus = 'MISSED' WHERE date < :todayDate AND clockOutStatus = 'PENDING'")
    suspend fun markPastPendingClockOutAsMissed(todayDate: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendanceRecord(record: AttendanceRecord)

    @Update
    suspend fun updateAttendanceRecord(record: AttendanceRecord)

    @Query("DELETE FROM attendance_records")
    suspend fun clearAllRecords()
}
