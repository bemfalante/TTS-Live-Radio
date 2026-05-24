package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "radio_settings")
data class RadioSettings(
    @PrimaryKey val id: Int = 1,
    val host: String = "stream.zeno.fm",
    val port: Int = 80,
    val mountpoint: String = "",
    val username: String = "source",
    val password: String = "",
    val autoStreamOnSpace: Boolean = true,
    val localVoiceMonitor: Boolean = true,
    val ttsEngine: String = "WEB", // "WEB", "LOCAL", "GEMINI"
    val geminiVoice: String = "Kore" // "Puck", "Charon", "Kore", "Fenrir", "Aoede"
)

@Entity(tableName = "broadcast_history")
data class BroadcastHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
