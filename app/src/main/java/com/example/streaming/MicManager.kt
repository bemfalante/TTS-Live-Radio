package com.example.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MicManager(
    private val context: Context,
    private val onAudioChunk: (ShortArray) -> Unit
) {
    private val TAG = "MicManager"
    private val SAMPLE_RATE = 16000 // 16kHz for AacStreamer alignment
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (!hasRecordPermission()) {
            Log.e(TAG, "RECORD_AUDIO Permission is missing")
            return false
        }
        if (_isRecording.value) {
            return true
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            val bufferSize = Math.max(minBufferSize, 4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            _isRecording.value = true

            recordingJob = scope.launch {
                val buffer = ShortArray(1024)
                while (isActive && _isRecording.value) {
                    val record = audioRecord ?: break
                    val readSize = record.read(buffer, 0, buffer.size)
                    if (readSize > 0) {
                        val chunk = ShortArray(readSize)
                        System.arraycopy(buffer, 0, chunk, 0, readSize)
                        onAudioChunk(chunk)
                    } else if (readSize < 0) {
                        Log.e(TAG, "AudioRecord read error: $readSize")
                        break
                    }
                }
                stopInternal()
            }

            Log.d(TAG, "Microphone live voice capture started.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting microphone recording", e)
            stopRecording()
            return false
        }
    }

    fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
        stopInternal()
        Log.d(TAG, "Microphone live voice capture stopped.")
    }

    private fun stopInternal() {
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audioRecord", e)
        }
        audioRecord = null
    }
}
