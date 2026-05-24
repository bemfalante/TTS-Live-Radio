package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RadioDao {
    @Query("SELECT * FROM radio_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<RadioSettings?>

    @Query("SELECT * FROM radio_settings WHERE id = 1")
    suspend fun getSettings(): RadioSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: RadioSettings)

    @Query("SELECT * FROM broadcast_history ORDER BY timestamp DESC LIMIT 50")
    fun getHistoryFlow(): Flow<List<BroadcastHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: BroadcastHistory)

    @Query("DELETE FROM broadcast_history")
    suspend fun clearHistory()
}
