package com.example.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

enum class StreamState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class AacStreamer {
    private val TAG = "AacStreamer"

    // Set constant output streaming parameters
    val STREAM_SAMPLE_RATE = 16000 // 16kHz is ideal for voices & works on low-bandwidth Android Go
    val STREAM_CHANNELS = 1
    private val STREAM_BITRATE = 32000 // 32kbps mono AAC is crystal clear for speech
    private val FRAME_SIZE = 1024 // Standard AAC LC frame size in samples

    private val _streamState = MutableStateFlow(StreamState.DISCONNECTED)
    val streamState = _streamState.asStateFlow()

    private val _liveAmplitude = MutableStateFlow(0f)
    val liveAmplitude = _liveAmplitude.asStateFlow()

    private val _isStreamingSpeech = MutableStateFlow(false)
    val isStreamingSpeech = _isStreamingSpeech.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val pcmQueue = ConcurrentLinkedQueue<ShortArray>()
    private var currentPendingSamples = ShortArray(0)
    private var pendingOffset = 0

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var encoder: MediaCodec? = null

    private var streamJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun queuePcm(pcm: ShortArray) {
        pcmQueue.add(pcm)
    }

    fun connect(host: String, port: Int, mountpoint: String, username: String, password: String) {
        if (_streamState.value == StreamState.CONNECTED || _streamState.value == StreamState.CONNECTING) {
            return
        }

        _streamState.value = StreamState.CONNECTING
        _error.value = null
        _liveAmplitude.value = 0f

        streamJob = scope.launch {
            try {
                Log.d(TAG, "Connecting to $host:$port$mountpoint...")
                socket = Socket(host, port)
                socket?.tcpNoDelay = true
                socket?.soTimeout = 10000 // 10s connection timeout for reliability

                val outStream = socket!!.getOutputStream()
                val inStream = socket!!.getInputStream()

                // Execute the Icecast Source Handshake
                val cleanMount = if (mountpoint.startsWith("/")) mountpoint else "/$mountpoint"
                val authString = "$username:$password"
                val authBase64 = Base64.encodeToString(authString.toByteArray(), Base64.NO_WRAP)

                val handshake = StringBuilder()
                handshake.append("PUT $cleanMount HTTP/1.1\r\n")
                handshake.append("Host: $host:$port\r\n")
                handshake.append("User-Agent: TTSLiveRadio/1.0\r\n")
                handshake.append("Authorization: Basic $authBase64\r\n")
                handshake.append("Content-Type: audio/aac\r\n")
                handshake.append("Ice-Name: TTS Live Radio Stream\r\n")
                handshake.append("Ice-Public: 0\r\n")
                handshake.append("Ice-Description: Live stream generated from Text-To-Speech\r\n")
                handshake.append("\r\n")

                outStream.write(handshake.toString().toByteArray())
                outStream.flush()

                // Read handshake response
                val reader = BufferedReader(InputStreamReader(inStream))
                val responseLine = reader.readLine()
                Log.d(TAG, "Server response: $responseLine")

                if (responseLine == null || (!responseLine.contains("200") && !responseLine.contains("100"))) {
                    throw Exception(responseLine ?: "No response from streaming server")
                }

                // Consume any remaining headers
                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }

                outputStream = outStream
                setupEncoder()

                _streamState.value = StreamState.CONNECTED
                Log.d(TAG, "Connected to broadcaster. Running streaming loop...")

                startStreamingLoop()

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _streamState.value = StreamState.ERROR
                _error.value = e.localizedMessage ?: "Failed to connect to Icecast server"
                cleanup()
            }
        }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, STREAM_SAMPLE_RATE, STREAM_CHANNELS)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, STREAM_BITRATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder?.start()
    }

    private fun startStreamingLoop() {
        val frameDurationMs = (FRAME_SIZE.toDouble() / STREAM_SAMPLE_RATE * 1000.0).toLong() // 64ms for 16kHz
        val encoderInstance = encoder ?: return
        val outStream = outputStream ?: return

        val bufferInfo = MediaCodec.BufferInfo()
        var presentationTimeUs = 0L

        while (_streamState.value == StreamState.CONNECTED) {
            val startTime = System.currentTimeMillis()

            // 1. Fetch next frame of audio (voice or silence)
            val currentSpeechActive = pcmQueue.isNotEmpty() || (pendingOffset < currentPendingSamples.size)
            _isStreamingSpeech.value = currentSpeechActive

            val pcmFrame = getNextFrame(FRAME_SIZE)

            // Convert shorts to bytes for MediaCodec (Little Endian format)
            val byteFrame = ByteArray(pcmFrame.size * 2)
            var rmsSum = 0.0
            for (i in pcmFrame.indices) {
                val sample = pcmFrame[i]
                byteFrame[2 * i] = (sample.toInt() and 0xFF).toByte()
                byteFrame[2 * i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                rmsSum += sample.toDouble() * sample.toDouble()
            }

            // Calculate RMS Amplitude for visualizer
            val rms = Math.sqrt(rmsSum / pcmFrame.size)
            val mappedAmplitude = (rms / 9000.0).coerceIn(0.0, 1.0).toFloat()
            _liveAmplitude.value = mappedAmplitude

            // 2. Feed PCM Frame into MediaCodec input buffer
            try {
                val inputBufferIndex = encoderInstance.dequeueInputBuffer(1000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = encoderInstance.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(byteFrame)
                        encoderInstance.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            byteFrame.size,
                            presentationTimeUs,
                            0
                        )
                        presentationTimeUs += frameDurationMs * 1000L
                    }
                }

                // 3. Fetch encoded AAC and prefix with ADTS header
                var outputBufferIndex = encoderInstance.dequeueOutputBuffer(bufferInfo, 1000)
                while (outputBufferIndex >= 0) {
                    val outputBuffer = encoderInstance.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        val outData = ByteArray(bufferInfo.size)
                        outputBuffer.get(outData)

                        // ADTS Header prepended for streaming broadcast format
                        val adtsPacket = ByteArray(outData.size + 7)
                        addADTStoPacket(adtsPacket, adtsPacket.size, STREAM_SAMPLE_RATE, STREAM_CHANNELS)
                        System.arraycopy(outData, 0, adtsPacket, 7, outData.size)

                        // Broadcast written to network
                        outStream.write(adtsPacket)
                        outStream.flush()
                    }
                    encoderInstance.releaseOutputBuffer(outputBufferIndex, false)
                    outputBufferIndex = encoderInstance.dequeueOutputBuffer(bufferInfo, 0)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Streaming loop encryption failed", e)
                _streamState.value = StreamState.ERROR
                _error.value = "Streaming channel interrupted: ${e.localizedMessage}"
                break
            }

            // Exactly pacing transmission rate
            val elapsedTime = System.currentTimeMillis() - startTime
            val sleepTime = frameDurationMs - elapsedTime
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (ignored: InterruptedException) {
                    break
                }
            }
        }

        cleanup()
    }

    private fun getNextFrame(frameSize: Int): ShortArray {
        val frame = ShortArray(frameSize)
        var frameOffset = 0

        while (frameOffset < frameSize) {
            if (pendingOffset < currentPendingSamples.size) {
                val available = currentPendingSamples.size - pendingOffset
                val needed = frameSize - frameOffset
                val toCopy = Math.min(available, needed)
                System.arraycopy(currentPendingSamples, pendingOffset, frame, frameOffset, toCopy)
                pendingOffset += toCopy
                frameOffset += toCopy
            } else {
                val nextChunk = pcmQueue.poll()
                if (nextChunk != null) {
                    currentPendingSamples = nextChunk
                    pendingOffset = 0
                } else {
                    // Send pure silence (0s) to keep streaming connection continuous
                    while (frameOffset < frameSize) {
                        frame[frameOffset] = 0
                        frameOffset++
                    }
                }
            }
        }
        return frame
    }

    private fun addADTStoPacket(packet: ByteArray, packetLen: Int, sampleRate: Int, channels: Int) {
        val profile = 2 // AAC LC
        val freqIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 4 // fallback 44100
        }
        val chanCfg = channels

        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte() // standard MPEG-2 representation for simplicity & compatibility
        packet[2] = (((profile - 1) shl 6) or (freqIdx shl 2) or (chanCfg shr 1)).toByte()
        packet[3] = (((chanCfg and 1) shl 7) or (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) or 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    fun disconnect() {
        if (_streamState.value == StreamState.DISCONNECTED) return
        _streamState.value = StreamState.DISCONNECTED
        streamJob?.cancel()
        _liveAmplitude.value = 0f
        _isStreamingSpeech.value = false
    }

    private fun cleanup() {
        try {
            encoder?.stop()
            encoder?.release()
        } catch (ignored: Exception) {}
        encoder = null

        try {
            socket?.close()
        } catch (ignored: Exception) {}
        socket = null
        outputStream = null

        pcmQueue.clear()
        currentPendingSamples = ShortArray(0)
        pendingOffset = 0
    }
}
