package com.example.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.RadioRepository
import com.example.data.RadioSettings
import com.example.streaming.AacStreamer
import com.example.streaming.StreamState
import com.example.streaming.TtsManager
import com.example.streaming.WavParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class RadioViewModel(
    application: Application,
    private val repository: RadioRepository
) : AndroidViewModel(application) {

    private val TAG = "RadioViewModel"

    // Core streaming services
    private val aacStreamer = AacStreamer()
    private val ttsManager = TtsManager(application) { pcmData, sampleRate ->
        // On PCM synthesized from TTS, resample to stream sample rate and queue in the streamer
        val targetRate = aacStreamer.STREAM_SAMPLE_RATE
        val resampled = WavParser.resample(pcmData, sampleRate, targetRate)
        Log.d(TAG, "Queuing synthesized speech PCM segment: original size=${pcmData.size} at ${sampleRate}Hz, resampled size=${resampled.size} at ${targetRate}Hz")
        aacStreamer.queuePcm(resampled)
    }

    // Monitoring playback player
    private var mediaPlayer: MediaPlayer? = null
    private val _isLiveMonitoring = MutableStateFlow(false)
    val isLiveMonitoring = _isLiveMonitoring.asStateFlow()

    // Screen states
    private val _typedText = MutableStateFlow("")
    val typedText = _typedText.asStateFlow()

    private var streamedIndex = 0

    // Voice customisation
    private val _pitch = MutableStateFlow(1.0f)
    val pitch = _pitch.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed = _speed.asStateFlow()

    // Settings and history bindings from Room
    val settingsState: StateFlow<RadioSettings> = repository.settingsFlow
        .map { it ?: RadioSettings() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = RadioSettings()
        )

    val historyState = repository.historyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Bridge streamer flows to UI
    val streamState: StateFlow<StreamState> = aacStreamer.streamState
    val liveAmplitude: StateFlow<Float> = aacStreamer.liveAmplitude
    val isStreamingSpeech: StateFlow<Boolean> = aacStreamer.isStreamingSpeech
    val streamerError: StateFlow<String?> = aacStreamer.error

    // Bridge TTS flows to UI
    val isTtsInitialized = ttsManager.isInitialized
    val availableLocales = ttsManager.availableLocales
    val currentLocale = ttsManager.currentLocale

    private val _localMonitorError = MutableStateFlow<String?>(null)
    val localMonitorError = _localMonitorError.asStateFlow()

    fun updateTypedText(newText: String) {
        _typedText.value = newText

        val settings = settingsState.value
        if (settings.autoStreamOnSpace) {
            processRealtimeTextInput(newText, settings)
        }
    }

    private fun processRealtimeTextInput(text: String, settings: RadioSettings) {
        // Reset tracking index if text is shorter than what we've processed
        if (text.length < streamedIndex) {
            streamedIndex = text.length
            return
        }

        if (text.length > streamedIndex) {
            val lastChar = text.last()
            // Word boundaries triggers streaming
            if (lastChar == ' ' || lastChar == '.' || lastChar == '?' || lastChar == '!' || lastChar == ',' || lastChar == '\n') {
                val phrase = text.substring(streamedIndex, text.length).trim()
                if (phrase.isNotEmpty()) {
                    triggerTtsSpeech(phrase, settings)
                }
                streamedIndex = text.length
            }
        }
    }

    fun triggerManualSpeech() {
        val text = _typedText.value.trim()
        val settings = settingsState.value
        if (text.isNotEmpty()) {
            triggerTtsSpeech(text, settings)
            // Save inside history log
            viewModelScope.launch {
                repository.addHistory(text)
            }
            // Clear or keep text based on flow preference (let's keep but fully slide streamedIndex to end limit)
            streamedIndex = _typedText.value.length
        }
    }

    fun triggerPhraseSpeech(phrase: String) {
        val settings = settingsState.value
        triggerTtsSpeech(phrase, settings)
        viewModelScope.launch {
            repository.addHistory(phrase)
        }
    }

    private fun triggerTtsSpeech(phrase: String, settings: RadioSettings) {
        if (streamState.value != StreamState.CONNECTED) {
            Log.w(TAG, "Tts synthesis suppressed since radio is not connected")
            // Make a local monitoring speech voice cue anyway so that they know they aren't on stream
            ttsManager.speak(phrase, pitch.value, speed.value, localMonitor = true)
            return
        }

        // Send down to TTS system using current voice configurations
        ttsManager.speak(
            text = phrase,
            pitch = _pitch.value,
            speed = _speed.value,
            localMonitor = settings.localVoiceMonitor
        )
    }

    fun setLanguage(locale: Locale) {
        ttsManager.setLanguage(locale)
    }

    fun updateVoiceTuning(pitch: Float, speed: Float) {
        _pitch.value = pitch
        _speed.value = speed
    }

    // Server Broadcast operations
    fun connectStream() {
        viewModelScope.launch {
            val settings = repository.getSettings()
            aacStreamer.connect(
                host = settings.host,
                port = settings.port,
                mountpoint = settings.mountpoint,
                username = settings.username,
                password = settings.password
            )
        }
    }

    fun disconnectStream() {
        aacStreamer.disconnect()
        stopLiveNetworkMonitor()
    }

    // Configuration CRUD
    fun saveSettings(newSettings: RadioSettings) {
        viewModelScope.launch {
            repository.saveSettings(newSettings)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    // Direct Live stream URL Listener
    fun toggleLiveNetworkMonitor(playbackUrl: String) {
        if (_isLiveMonitoring.value) {
            stopLiveNetworkMonitor()
        } else {
            startLiveNetworkMonitor(playbackUrl)
        }
    }

    private fun startLiveNetworkMonitor(playbackUrl: String) {
        _localMonitorError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stopLiveNetworkMonitor()
                val player = MediaPlayer()
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                player.setDataSource(playbackUrl)
                player.prepare()
                player.start()
                mediaPlayer = player
                _isLiveMonitoring.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect monitor player", e)
                _localMonitorError.value = "Failed to stream live feedback. Please verify host and broadcast status."
                _isLiveMonitoring.value = false
            }
        }
    }

    private fun stopLiveNetworkMonitor() {
        mediaPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (ignored: Exception) {}
        }
        mediaPlayer = null
        _isLiveMonitoring.value = false
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
        aacStreamer.disconnect()
        stopLiveNetworkMonitor()
    }
}

class RadioViewModelFactory(
    private val application: Application,
    private val repository: RadioRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RadioViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RadioViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
