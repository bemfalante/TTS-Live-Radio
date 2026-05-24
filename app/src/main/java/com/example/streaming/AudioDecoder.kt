package com.example.streaming

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File

object AudioDecoder {
    private const val TAG = "AudioDecoder"

    fun decodeToPcm(inputFile: File, targetSampleRate: Int): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(inputFile.absolutePath)
            
            // Find audio track
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                Log.e(TAG, "No audio track found in file")
                return null
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmDataList = mutableListOf<ShortArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnd = false
            var outputEnd = false

            while (!outputEnd) {
                if (!inputEnd) {
                    val inputBufferIndex = codec.dequeueInputBuffer(5000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnd = true
                            } else {
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 5000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val shortBuffer = outputBuffer.asShortBuffer()
                        val shorts = ShortArray(bufferInfo.size / 2)
                        shortBuffer.get(shorts)
                        pcmDataList.add(shorts)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEnd = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "Decoder output format changed: $newFormat")
                }
            }

            // 1. Concatenate all segments into a single ShortArray
            val totalShortsCount = pcmDataList.sumOf { it.size }
            if (totalShortsCount == 0) return null

            val rawPcm = ShortArray(totalShortsCount)
            var offset = 0
            for (shorts in pcmDataList) {
                System.arraycopy(shorts, 0, rawPcm, offset, shorts.size)
                offset += shorts.size
            }

            // 2. Resample if sample rate doesn't match target
            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Convert multi-channel to mono if needed
            var monoPcm = rawPcm
            if (channels > 1) {
                val monoSize = rawPcm.size / channels
                monoPcm = ShortArray(monoSize)
                for (i in 0 until monoSize) {
                    var sum = 0
                    for (c in 0 until channels) {
                        sum += rawPcm[i * channels + c]
                    }
                    monoPcm[i] = (sum / channels).toShort()
                }
            }

            return WavParser.resample(monoPcm, sourceSampleRate, targetSampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding file: ${inputFile.name}", e)
            return null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (ignored: Exception) {}
            try {
                extractor.release()
            } catch (ignored: Exception) {}
        }
    }
}
