package com.example.streaming

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

class TtsManager(
    private val context: Context,
    private val onPcmSynthesized: (ShortArray, Int) -> Unit
) : TextToSpeech.OnInitListener {
    private val TAG = "TtsManager"
    private var tts: TextToSpeech? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    private val _availableLocales = MutableStateFlow<List<Locale>>(emptyList())
    val availableLocales = _availableLocales.asStateFlow()

    private val _currentLocale = MutableStateFlow(Locale.US)
    val currentLocale = _currentLocale.asStateFlow()

    private val tempSpeechFile = File(context.cacheDir, "tts_temp_broadcast.wav")

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine != null) {
                // Discover languages
                val locales = mutableListOf<Locale>()
                try {
                    val available = Locale.getAvailableLocales()
                    for (locale in available) {
                        val isLang = engine.isLanguageAvailable(locale)
                        if (isLang >= TextToSpeech.LANG_AVAILABLE) {
                            locales.add(locale)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking languages", e)
                }

                // If empty fallback to default standard ones
                if (locales.isEmpty()) {
                    locales.addAll(listOf(Locale.US, Locale.UK, Locale.FRANCE, Locale.GERMANY, Locale.ITALY))
                }

                _availableLocales.value = locales.sortedBy { it.displayName }
                _isInitialized.value = true

                // Default setup
                engine.language = Locale.US
                setupProgressListener()
            }
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS synthesis started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS synthesis complete: $utteranceId")
                if (utteranceId != null && utteranceId.startsWith("stream_")) {
                    // Raw parsing and resampling triggered when complete
                    val info = WavParser.parseWav(tempSpeechFile)
                    if (info != null && info.pcmShorts.isNotEmpty()) {
                        onPcmSynthesized(info.pcmShorts, info.sampleRate)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS synthesis error: $utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS synthesis error: $utteranceId, Code: $errorCode")
            }
        })
    }

    fun setLanguage(locale: Locale) {
        _currentLocale.value = locale
        tts?.language = locale
    }

    fun speak(
        text: String,
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
        localMonitor: Boolean = true
    ) {
        val engine = tts ?: return
        if (!_isInitialized.value) return

        try {
            engine.setPitch(pitch)
            engine.setSpeechRate(speed)

            val utteranceId = "stream_${System.currentTimeMillis()}"
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

            // 1. Always Synthesize to file for the Icecast audio live stream
            try {
                if (tempSpeechFile.exists()) {
                    tempSpeechFile.delete()
                }
            } catch (ignored: Exception) {}

            engine.synthesizeToFile(text, params, tempSpeechFile, utteranceId)

            // 2. Play on local speakers (speaker monitor status checklist)
            if (localMonitor) {
                val localUtteranceId = "local_${System.currentTimeMillis()}"
                engine.speak(text, TextToSpeech.QUEUE_ADD, null, localUtteranceId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS error speaking text", e)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
