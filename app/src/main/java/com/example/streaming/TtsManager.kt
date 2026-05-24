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
    private val onPcmSynthesized: (text: String, pcmData: ShortArray, sampleRate: Int) -> Unit
) : TextToSpeech.OnInitListener {
    private val utteranceTextMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val TAG = "TtsManager"
    private var tts: TextToSpeech? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    private val _availableLocales = MutableStateFlow<List<Locale>>(emptyList())
    val availableLocales = _availableLocales.asStateFlow()

    private val _currentLocale = MutableStateFlow(Locale("pt", "BR"))
    val currentLocale = _currentLocale.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    // Discover languages safely and rapidly
                    val locales = mutableListOf<Locale>()
                    try {
                        val available = engine.availableLanguages
                        if (available != null && available.isNotEmpty()) {
                            locales.addAll(available)
                        } else {
                            // Fallback to testing only standard common languages to avoid long loops
                            val commonLocales = listOf(
                                Locale("pt", "BR"),
                                Locale.US, Locale.UK, Locale.CANADA, 
                                Locale.FRANCE, Locale.GERMANY, Locale.ITALY, 
                                Locale.CHINESE, Locale.JAPANESE, Locale.KOREAN,
                                Locale("es", "ES")
                            )
                            for (locale in commonLocales) {
                                val availability = try { engine.isLanguageAvailable(locale) } catch (e: Exception) { -1 }
                                if (availability >= TextToSpeech.LANG_AVAILABLE) {
                                    locales.add(locale)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking languages", e)
                    }

                    // Ensure Portuguese (Brazil) and English (US) are ALWAYS in the available locales list!
                    val forcedLocales = listOf(Locale("pt", "BR"), Locale.US)
                    for (l in forcedLocales) {
                        if (!locales.any { it.language == l.language && it.country == l.country }) {
                            locales.add(l)
                        }
                    }

                    _availableLocales.value = locales.sortedBy { it.displayName }
                    _isInitialized.value = true

                    // Setup user's default locale, prioritize pt_BR or system locale
                    val ptBr = Locale("pt", "BR")
                    val isPtAvailable = try { engine.isLanguageAvailable(ptBr) } catch (e: Exception) { -1 }
                    if (isPtAvailable >= TextToSpeech.LANG_AVAILABLE) {
                        engine.language = ptBr
                        _currentLocale.value = ptBr
                        Log.d(TAG, "Successfully configured Portuguese (Brazil) as default TTS language")
                    } else {
                        // Even if not officially marked, try setting it! Or fallback to US but keep pt_BR as select option
                        val result = try { engine.setLanguage(ptBr) } catch (e: Exception) { -1 }
                        if (result >= TextToSpeech.LANG_AVAILABLE) {
                            _currentLocale.value = ptBr
                            Log.d(TAG, "Forced Portuguese (Brazil) as default TTS language")
                        } else {
                            Log.w(TAG, "Portuguese (Brazil) is not supported by this engine. Defaulting to US English.")
                            engine.language = Locale.US
                            _currentLocale.value = Locale.US
                        }
                    }
                    setupProgressListener()
                } else {
                    Log.e(TAG, "TTS engine is null in deferred onInit handler")
                    _isInitialized.value = true
                }
            } else {
                Log.e(TAG, "TTS Initialization failed with status: $status")
                // Fallback to initialized so the UI is active and they can still try to type and speech trigger
                _isInitialized.value = true
            }
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
                    val text = utteranceTextMap.remove(utteranceId) ?: ""
                    val uniqueFile = File(context.cacheDir, "tts_${utteranceId}.wav")
                    val info = WavParser.parseWav(uniqueFile)
                    if (info != null && info.pcmShorts.isNotEmpty()) {
                        Log.d(TAG, "Parsed WAV file successfully: $utteranceId, channels=${info.channels}, sampleRate=${info.sampleRate}, size=${info.pcmShorts.size}")
                        onPcmSynthesized(text, info.pcmShorts, info.sampleRate)
                    } else {
                        Log.e(TAG, "Failed or empty WAV file parsed for: $utteranceId, exists=${uniqueFile.exists()}, length=${uniqueFile.length()}")
                    }
                    // Delete synthesized WAV file since it is already loaded in memory
                    try {
                        if (uniqueFile.exists()) {
                            uniqueFile.delete()
                        }
                    } catch (ignored: Exception) {}
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
        val engine = tts ?: return
        val check = try { engine.isLanguageAvailable(locale) } catch (e: Exception) { -1 }
        val isSupported = check >= TextToSpeech.LANG_AVAILABLE
        val targetLocale = if (isSupported) locale else Locale.US
        
        _currentLocale.value = targetLocale
        val result = try { engine.setLanguage(targetLocale) } catch (e: Exception) { -1 }
        Log.d(TAG, "setLanguage target: $locale, supported: $isSupported, set to: $targetLocale, result code: $result")
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
            var selectedLocale = _currentLocale.value
            val check = try { engine.isLanguageAvailable(selectedLocale) } catch (e: Exception) { -1 }
            if (check < TextToSpeech.LANG_AVAILABLE) {
                Log.w(TAG, "Locale $selectedLocale not available. Using Locale.US")
                selectedLocale = Locale.US
            }

            val langResult = try { engine.setLanguage(selectedLocale) } catch (e: Exception) { -1 }
            Log.d(TAG, "Enforced language $selectedLocale with result code: $langResult")
            
            engine.setPitch(pitch)
            engine.setSpeechRate(speed)

            val utteranceId = "stream_${System.currentTimeMillis()}"
            utteranceTextMap[utteranceId] = text
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

            val uniqueFile = File(context.cacheDir, "tts_${utteranceId}.wav")
            val result = try { engine.synthesizeToFile(text, params, uniqueFile, utteranceId) } catch (e: Exception) { -1 }
            Log.d(TAG, "synthesizeToFile returned: $result for text: '$text', file: ${uniqueFile.name}")

            // 2. Play on local speakers (speaker monitor status checklist)
            if (localMonitor) {
                val localUtteranceId = "local_${System.currentTimeMillis()}"
                try {
                    engine.speak(text, TextToSpeech.QUEUE_ADD, null, localUtteranceId)
                } catch (e: Exception) {
                    Log.e(TAG, "engine.speak failed", e)
                }
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
