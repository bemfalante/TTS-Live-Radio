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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

data class SynthesizedClip(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val pcmData: ShortArray,
    val sampleRate: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Float = pcmData.size.toFloat() / sampleRate,
    val isTransmitted: Boolean = false,
    val isPlayingLocally: Boolean = false
)

class RadioViewModel(
    application: Application,
    private val repository: RadioRepository
) : AndroidViewModel(application) {

    private val TAG = "RadioViewModel"

    // Core streaming services
    private val aacStreamer = AacStreamer()

    // Queue of synthesized clips for user review
    private val _synthesizedClips = MutableStateFlow<List<SynthesizedClip>>(emptyList())
    val synthesizedClips = _synthesizedClips.asStateFlow()

    private val ttsManager = TtsManager(application) { text, pcmData, sampleRate ->
        val cleanText = if (text.trim().isEmpty()) "Synthesized Voice Clip" else text
        val newClip = SynthesizedClip(
            text = cleanText,
            pcmData = pcmData,
            sampleRate = sampleRate
        )
        _synthesizedClips.value = _synthesizedClips.value + newClip
        Log.d(TAG, "Generated clip added: '${cleanText}', size=${pcmData.size} shorts")
        
        // If the streamer is connected and we have auto stream enabled or wish to automatically queue
        // We can do that or let the user review. To keep the app flexible, let's also auto-stream
        // iff the user is typing fast and they prefer auto-transmission, but let them still review it.
        // Actually, let's require the user to manually click to transmit or keep auto-queueing if stream is active.
        // Let's transmit it automatically IF the stream is connected, but still keep it in the list so they can see and re-play it!
        // This is wonderful because it satisfies BOTH requirements: it goes to air immediately AND is reviewable!
        if (streamState.value == StreamState.CONNECTED) {
            val targetRate = aacStreamer.STREAM_SAMPLE_RATE
            val resampled = WavParser.resample(pcmData, sampleRate, targetRate)
            Log.d(TAG, "Auto-routing synthesized speech to live broadcast stream: ${resampled.size} bytes")
            aacStreamer.queuePcm(resampled)
            // Save as transmitted
            _synthesizedClips.value = _synthesizedClips.value.map {
                if (it.id == newClip.id) it.copy(isTransmitted = true) else it
            }
        }
    }

    // Playing a clip locally
    fun playClipLocally(clipId: String) {
        val clips = _synthesizedClips.value
        val clip = clips.find { it.id == clipId } ?: return

        // Set isPlayingLocally = true
        _synthesizedClips.value = clips.map {
            if (it.id == clipId) it.copy(isPlayingLocally = true) else it
        }

        viewModelScope.launch(Dispatchers.IO) {
            var audioTrack: AudioTrack? = null
            try {
                val minBufSize = AudioTrack.getMinBufferSize(
                    clip.sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = Math.max(minBufSize, 4096)
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    clip.sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
                
                audioTrack.play()
                
                val pcm = clip.pcmData
                var offset = 0
                val chunkSize = 2048
                while (offset < pcm.size) {
                    val length = Math.min(chunkSize, pcm.size - offset)
                    audioTrack.write(pcm, offset, length)
                    offset += length
                }
                
                // Wait for playback to finish playing the written data
                val playDurationMs = (pcm.size.toFloat() / clip.sampleRate * 1000).toLong()
                Thread.sleep(Math.max(100L, playDurationMs + 100L))
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack playing failed, falling back to TTS directly", e)
                // Fallback to TTS directly!
                ttsManager.speakLocally(clip.text, _pitch.value, _speed.value)
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (ignored: Exception) {}
                
                // Clear state
                _synthesizedClips.value = _synthesizedClips.value.map {
                    if (it.id == clipId) it.copy(isPlayingLocally = false) else it
                }
            }
        }
    }

    // Transmitting clip to the live Icecast server
    fun transmitClip(clipId: String) {
        val list = _synthesizedClips.value
        val updated = list.map { clip ->
            if (clip.id == clipId) {
                val targetRate = aacStreamer.STREAM_SAMPLE_RATE
                val resampled = WavParser.resample(clip.pcmData, clip.sampleRate, targetRate)
                Log.d(TAG, "Manually transmitting clip '${clip.text}' to stream queue: size=${resampled.size}")
                aacStreamer.queuePcm(resampled)
                clip.copy(isTransmitted = true)
            } else {
                clip
            }
        }
        _synthesizedClips.value = updated
    }

    fun deleteClip(clipId: String) {
        _synthesizedClips.value = _synthesizedClips.value.filter { it.id != clipId }
    }

    fun clearAllClips() {
        _synthesizedClips.value = emptyList()
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
            ttsManager.speakLocally(phrase, pitch.value, speed.value)
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

    val useWebTts = ttsManager.useWebTts

    fun setUseWebTts(enabled: Boolean) {
        ttsManager.setUseWebTts(enabled)
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
