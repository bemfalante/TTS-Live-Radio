package com.example.data

import kotlinx.coroutines.flow.Flow

class RadioRepository(private val radioDao: RadioDao) {
    val settingsFlow: Flow<RadioSettings?> = radioDao.getSettingsFlow()
    val historyFlow: Flow<List<BroadcastHistory>> = radioDao.getHistoryFlow()

    suspend fun getSettings(): RadioSettings {
        return radioDao.getSettings() ?: RadioSettings()
    }

    suspend fun saveSettings(settings: RadioSettings) {
        radioDao.saveSettings(settings)
    }

    suspend fun addHistory(text: String) {
        if (text.isNotBlank()) {
            radioDao.insertHistory(BroadcastHistory(text = text.trim()))
        }
    }

    suspend fun clearHistory() {
        radioDao.clearHistory()
    }
}
