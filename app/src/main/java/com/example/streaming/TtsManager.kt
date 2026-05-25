package com.example.streaming

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class TtsManager(
    private val context: Context,
    private val onPcmSynthesized: (text: String, pcmData: ShortArray, sampleRate: Int) -> Unit
) : TextToSpeech.OnInitListener {
    private val utteranceTextMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val TAG = "TtsManager"
    private var tts: TextToSpeech? = null
    private var engineUsed = "com.google.android.tts"

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    private val _availableLocales = MutableStateFlow<List<Locale>>(emptyList())
    val availableLocales = _availableLocales.asStateFlow()

    private val _currentLocale = MutableStateFlow(Locale("pt", "BR"))
    val currentLocale = _currentLocale.asStateFlow()

    // Highly reliable high-quality Google Web TTS voice fallback toggle
    private val _useWebTts = MutableStateFlow(true)
    val useWebTts = _useWebTts.asStateFlow()

    init {
        initTts(true)
    }

    private fun initTts(tryGoogleFirst: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                if (tryGoogleFirst) {
                    engineUsed = "com.google.android.tts"
                    tts = TextToSpeech(context.applicationContext, this, "com.google.android.tts")
                    Log.d(TAG, "Trying to initialize Google TTS engine")
                } else {
                    engineUsed = "default"
                    tts = TextToSpeech(context.applicationContext, this)
                    Log.d(TAG, "Trying to initialize system default TTS engine")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating TextToSpeech", e)
                if (tryGoogleFirst) {
                    initTts(false)
                } else {
                    _isInitialized.value = true
                }
            }
        }
    }

    override fun onInit(status: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    Log.d(TAG, "TTS engine initialized successfully (Engine: $engineUsed)")
                    
                    // Discover languages
                    val locales = mutableListOf<Locale>()
                    try {
                        val available = engine.availableLanguages
                        if (available != null && available.isNotEmpty()) {
                            locales.addAll(available)
                        } else {
                            val common = listOf(
                                Locale("pt", "BR"), Locale.US, Locale.UK, Locale.CANADA,
                                Locale.FRANCE, Locale.GERMANY, Locale.ITALY, Locale("es", "ES")
                            )
                            for (l in common) {
                                val check = try { engine.isLanguageAvailable(l) } catch (e: Exception) { -1 }
                                if (check >= TextToSpeech.LANG_AVAILABLE) {
                                    locales.add(l)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking languages", e)
                    }

                    // Always ensure Portuguese (Brazil) and US English are visible options
                    val forced = listOf(Locale("pt", "BR"), Locale.US)
                    for (l in forced) {
                        if (!locales.any { it.language == l.language && it.country == l.country }) {
                            locales.add(l)
                        }
                    }

                    _availableLocales.value = locales.sortedBy { it.displayName }
                    _isInitialized.value = true

                    // Setup accurate language matching with fallback
                    val sysLocale = Locale.getDefault()
                    val ptBr = Locale("pt", "BR")
                    var bestLocale = sysLocale

                    val sysAvailability = try { engine.isLanguageAvailable(sysLocale) } catch (e: Exception) { -1 }
                    val ptBrAvailability = try { engine.isLanguageAvailable(ptBr) } catch (e: Exception) { -1 }

                    if (sysLocale.language == "pt" && sysAvailability >= TextToSpeech.LANG_AVAILABLE) {
                        bestLocale = sysLocale
                    } else if (ptBrAvailability >= TextToSpeech.LANG_AVAILABLE) {
                        bestLocale = ptBr
                    } else {
                        val ptGen = Locale("pt")
                        val ptGenAvailability = try { engine.isLanguageAvailable(ptGen) } catch (e: Exception) { -1 }
                        if (ptGenAvailability >= TextToSpeech.LANG_AVAILABLE) {
                            bestLocale = ptGen
                        } else {
                            val defaultLocale = try { engine.language } catch (e: Exception) { null }
                            bestLocale = defaultLocale ?: Locale.US
                        }
                    }

                    try {
                        engine.language = bestLocale
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception setting language onInit to $bestLocale", e)
                    }
                    _currentLocale.value = bestLocale
                    Log.d(TAG, "Selected matching language: $bestLocale")

                    setupProgressListener()
                } else {
                    Log.e(TAG, "TTS Engine was null inside onInit")
                    _isInitialized.value = true
                }
            } else {
                Log.e(TAG, "TTS Initialization failed: $status")
                if (engineUsed == "com.google.android.tts") {
                    Log.w(TAG, "Google TTS failed to initialize, retrying default system engine...")
                    try { tts?.shutdown() } catch (e: Exception) {}
                    initTts(false)
                } else {
                    _isInitialized.value = true
                }
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "Local TTS synthesis started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "Local TTS synthesis completed: $utteranceId")
                if (utteranceId != null && utteranceId.startsWith("stream_")) {
                    val text = utteranceTextMap.remove(utteranceId) ?: ""
                    val dir = context.externalCacheDir ?: context.cacheDir
                    val uniqueFile = File(dir, "tts_${utteranceId}.wav")
                    val info = WavParser.parseWav(uniqueFile)
                    if (info != null && info.pcmShorts.isNotEmpty()) {
                        Log.d(TAG, "Parsed WAV file successfully: chapters=${info.channels}, rate=${info.sampleRate}, length=${info.pcmShorts.size}")
                        onPcmSynthesized(text, info.pcmShorts, info.sampleRate)
                    } else {
                        Log.e(TAG, "Failed or empty WAV file parsed from local engine: ${uniqueFile.name}")
                    }
                    try {
                        if (uniqueFile.exists()) {
                            uniqueFile.delete()
                        }
                    } catch (ignored: Exception) {}
                }
            }

            @Deprecated("Deprecated")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Local TTS synthesis error: $utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "Local TTS synthesis error code: $errorCode for utterance: $utteranceId")
            }
        })
    }

    fun setLanguage(locale: Locale) {
        val engine = tts ?: return
        _currentLocale.value = locale
        try {
            val result = engine.setLanguage(locale)
            Log.d(TAG, "setLanguage called for: $locale, result code: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Error in setLanguage", e)
        }
    }

    fun setUseWebTts(enabled: Boolean) {
        _useWebTts.value = enabled
        Log.d(TAG, "setUseWebTts set to: $enabled")
    }

    fun speakLocally(
        text: String,
        engine: String = "WEB",
        voiceName: String = "Kore",
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
        geminiApiKey: String = ""
    ) {
        if (engine == "GEMINI") {
            speakWithGeminiEngine(text, voiceName, localMonitor = true, geminiApiKey = geminiApiKey)
            return
        }
        if (engine == "WEB") {
            speakWithWebEngine(text, localMonitor = true)
            return
        }

        val localEngine = tts ?: return
        if (!_isInitialized.value) return

        try {
            val selectedLocale = _currentLocale.value
            try {
                localEngine.language = selectedLocale
            } catch (e: Exception) {
                Log.e(TAG, "Error setting language $selectedLocale", e)
            }
            localEngine.setPitch(pitch)
            localEngine.setSpeechRate(speed)

            val localUtteranceId = "local_only_${System.currentTimeMillis()}"
            val result = try {
                localEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, localUtteranceId)
            } catch (e: Exception) {
                Log.e(TAG, "engine.speak locally failed", e)
                -1
            }
            Log.d(TAG, "speakLocally triggered text: '$text', result code: $result")
        } catch (e: Exception) {
            Log.e(TAG, "speakLocally exception", e)
        }
    }

    fun speak(
        text: String,
        engine: String = "WEB",
        voiceName: String = "Kore",
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
        localMonitor: Boolean = true,
        geminiApiKey: String = ""
    ) {
        if (engine == "GEMINI") {
            speakWithGeminiEngine(text, voiceName, localMonitor, geminiApiKey = geminiApiKey)
            return
        }
        if (engine == "WEB") {
            speakWithWebEngine(text, localMonitor)
            return
        }

        val localEngine = tts ?: return
        if (!_isInitialized.value) return

        try {
            val selectedLocale = _currentLocale.value
            try {
                localEngine.language = selectedLocale
            } catch (e: Exception) {
                Log.e(TAG, "Error setting language $selectedLocale", e)
            }
            localEngine.setPitch(pitch)
            localEngine.setSpeechRate(speed)

            val utteranceId = "stream_${System.currentTimeMillis()}"
            utteranceTextMap[utteranceId] = text
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

            val dir = context.externalCacheDir ?: context.cacheDir
            val uniqueFile = File(dir, "tts_${utteranceId}.wav")
            
            // Delete if exists before synthesizing as required by standard TTS
            try {
                if (uniqueFile.exists()) {
                    uniqueFile.delete()
                }
            } catch (ignored: Exception) {}

            val result = try {
                localEngine.synthesizeToFile(text, params, uniqueFile, utteranceId)
            } catch (e: Exception) {
                Log.e(TAG, "synthesizeToFile exception", e)
                -1
            }
            Log.d(TAG, "synthesizeToFile started: $result for text: '$text', file: ${uniqueFile.name}")

            if (localMonitor) {
                val localUtteranceId = "local_${System.currentTimeMillis()}"
                try {
                    localEngine.speak(text, TextToSpeech.QUEUE_ADD, null, localUtteranceId)
                } catch (e: Exception) {
                    Log.e(TAG, "engine.speak failed for local monitor", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS error speaking text", e)
        }
    }

    private fun speakWithGeminiEngine(text: String, voiceName: String, localMonitor: Boolean, geminiApiKey: String) {
        scope.launch {
            try {
                val utteranceId = "gemini_${System.currentTimeMillis()}"
                val dir = context.externalCacheDir ?: context.cacheDir
                val uniqueFile = File(dir, "tts_${utteranceId}.mp3")

                Log.d(TAG, "Gemini AI Studio TTS requested. Voice: $voiceName. text: '$text'")
                
                // Escape JSON text safely
                val safeText = text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")

                val jsonPayload = """
                    {
                        "contents": [
                            {
                                "parts": [
                                    {
                                        "text": "$safeText"
                                    }
                                ]
                            }
                        ],
                        "generationConfig": {
                            "responseModalities": ["AUDIO"],
                            "speechConfig": {
                                "voiceConfig": {
                                    "prebuiltVoiceConfig": {
                                        "voiceName": "$voiceName"
                                    }
                                }
                            }
                        }
                    }
                """.trimIndent()

                var apiKey = geminiApiKey.trim()
                if (apiKey.isEmpty()) {
                    apiKey = com.example.BuildConfig.GEMINI_API_KEY
                }

                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    Log.e(TAG, "Gemini API Key is empty or placeholder! Please ensure you have set GEMINI_API_KEY in App Settings.")
                    speakWithWebEngine(text, localMonitor)
                    return@launch
                }

                val urlString = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                conn.outputStream.use { os ->
                    val input = jsonPayload.toByteArray(charset("utf-8"))
                    os.write(input, 0, input.size)
                }

                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val base64DataPattern = java.util.regex.Pattern.compile("\"data\"\\s*:\\s*\"([^\"]+)\"")
                    val matcher = base64DataPattern.matcher(responseText)
                    if (matcher.find()) {
                        val base64Data = matcher.group(1) ?: ""
                        if (base64Data.isNotEmpty()) {
                            val audioBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                            uniqueFile.outputStream().use { output ->
                                output.write(audioBytes)
                            }
                            Log.d(TAG, "Successfully downloaded Gemini Audio Base64: ${audioBytes.size} bytes")

                            val pcmData = AudioDecoder.decodeToPcm(uniqueFile, 16000)
                            if (pcmData != null && pcmData.isNotEmpty()) {
                                Log.d(TAG, "Decoded Gemini TTS to Mono PCM: ${pcmData.size} shorts")
                                onPcmSynthesized(text, pcmData, 16000)
                                if (localMonitor) {
                                    playPcmLocally(pcmData, 16000)
                                }
                            } else {
                                Log.e(TAG, "Failed decoding Gemini base64 bytes to PCM mono shorts")
                            }
                        } else {
                            Log.e(TAG, "Found data field but base64 payload was empty")
                        }
                    } else {
                        Log.e(TAG, "Could not extract 'data' key with base64 audio contents from Gemini API JSON response")
                    }
                } else {
                    val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown HTTP error"
                    Log.e(TAG, "Gemini API rejected request: HTTP Code ${conn.responseCode}. Message: $errorText")
                    speakWithWebEngine(text, localMonitor)
                }

                try {
                    if (uniqueFile.exists()) {
                        uniqueFile.delete()
                    }
                } catch (ignored: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Gemini TTS processing query failed", e)
                speakWithWebEngine(text, localMonitor)
            }
        }
    }

    private fun speakWithWebEngine(text: String, localMonitor: Boolean) {
        scope.launch {
            try {
                val utteranceId = "web_${System.currentTimeMillis()}"
                val dir = context.externalCacheDir ?: context.cacheDir
                val uniqueFile = File(dir, "tts_${utteranceId}.mp3")

                Log.d(TAG, "Web TTS requested. Downloading track... text: '$text'")
                val urlEncoded = URLEncoder.encode(text, "UTF-8")

                // Map language to URL tags
                val selectedLocale = _currentLocale.value
                val langTag = if (selectedLocale.language == "pt") {
                    if (selectedLocale.country == "PT") "pt-PT" else "pt-BR"
                } else {
                    selectedLocale.toLanguageTag()
                }

                val urlString = "https://translate.google.com/translate_tts?ie=UTF-8&tl=$langTag&client=tw-ob&q=$urlEncoded"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 7000
                conn.readTimeout = 7000

                if (conn.responseCode == 200) {
                    conn.inputStream.use { input ->
                        uniqueFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Downloaded Web TTS successfully: ${uniqueFile.length()} bytes")

                    // Decode standard MP3 stream to 16kHz PCM mono shorts
                    val pcmData = AudioDecoder.decodeToPcm(uniqueFile, 16000)
                    if (pcmData != null && pcmData.isNotEmpty()) {
                        Log.d(TAG, "Decoded Web TTS to Mono PCM: ${pcmData.size} shorts")
                        
                        // Notify callback to stream or add to generated playlist reviews
                        onPcmSynthesized(text, pcmData, 16000)

                        if (localMonitor) {
                            playPcmLocally(pcmData, 16000)
                        }
                    } else {
                        Log.e(TAG, "Failed to decode MP3 file to PCM shorts")
                    }
                } else {
                    Log.e(TAG, "Google Web API rejected request: HTTP Code ${conn.responseCode}")
                }

                try {
                    if (uniqueFile.exists()) {
                        uniqueFile.delete()
                    }
                } catch (ignored: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Web TTS query failed", e)
            }
        }
    }

    fun playPcmLocally(pcmData: ShortArray, sampleRate: Int) {
        scope.launch {
            var audioTrack: AudioTrack? = null
            try {
                val minBufSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = Math.max(minBufSize, 4096)
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )

                audioTrack.play()

                var offset = 0
                val chunkSize = 2048
                while (offset < pcmData.size) {
                    val length = Math.min(chunkSize, pcmData.size - offset)
                    audioTrack.write(pcmData, offset, length)
                    offset += length
                }

                val playDurationMs = (pcmData.size.toFloat() / sampleRate * 1000).toLong()
                Thread.sleep(Math.max(100L, playDurationMs + 100L))
            } catch (e: Exception) {
                Log.e(TAG, "Error playing PCM audio track locally", e)
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (ignored: Exception) {}
            }
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
