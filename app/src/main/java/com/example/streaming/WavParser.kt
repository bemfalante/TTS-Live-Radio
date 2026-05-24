package com.example.streaming

import java.io.File

data class WavInfo(
    val channels: Int,
    val sampleRate: Int,
    val pcmShorts: ShortArray
)

object WavParser {
    fun parseWav(file: File): WavInfo? {
        if (!file.exists()) return null
        try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null

            // Check RIFF header is present by comparing byte values directly
            if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() || bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
                android.util.Log.e("WavParser", "Failed RIFF check: " + bytes.sliceArray(0..3).map { it.toInt().toChar() })
                return null
            }
            if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() || bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()) {
                android.util.Log.e("WavParser", "Failed WAVE check: " + bytes.sliceArray(8..11).map { it.toInt().toChar() })
                return null
            }

            var offset = 12
            var channels = 1
            var sampleRate = 16000
            var bitsPerSample = 16
            var dataOffset = 44
            var dataSize = 0

            while (offset + 8 <= bytes.size) {
                val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = readIntLE(bytes, offset + 4)
                offset += 8

                if (chunkId == "fmt ") {
                    channels = readShortLE(bytes, offset + 2)
                    sampleRate = readIntLE(bytes, offset + 4)
                    bitsPerSample = readShortLE(bytes, offset + 14)
                } else if (chunkId == "data") {
                    dataOffset = offset
                    dataSize = chunkSize
                    break
                }
                // Skip content
                offset += chunkSize
            }

            if (dataSize <= 0) {
                dataSize = bytes.size - dataOffset
            }
            val pcmLengthBytes = Math.min(dataSize, bytes.size - dataOffset)
            val pcmShortsCount = pcmLengthBytes / 2
            val pcmShorts = ShortArray(pcmShortsCount)

            // Convert Little Endian bytes to 16-bit Short samples
            for (i in 0 until pcmShortsCount) {
                val base = dataOffset + i * 2
                val low = bytes[base].toInt() and 0xFF
                val high = bytes[base + 1].toInt() and 0xFF
                pcmShorts[i] = ((high shl 8) or low).toShort()
            }

            return WavInfo(channels, sampleRate, pcmShorts)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun resample(input: ShortArray, inputSampleRate: Int, outputSampleRate: Int): ShortArray {
        if (inputSampleRate == outputSampleRate) return input
        val ratio = inputSampleRate.toDouble() / outputSampleRate.toDouble()
        val outputLength = (input.size / ratio).toInt()
        val output = ShortArray(outputLength)
        for (i in 0 until outputLength) {
            val srcIndex = i * ratio
            val index = srcIndex.toInt()
            val fraction = srcIndex - index
            if (index >= input.size - 1) {
                output[i] = input[input.size - 1]
            } else {
                val s1 = input[index].toInt()
                val s2 = input[index + 1].toInt()
                output[i] = (s1 + fraction * (s2 - s1)).toInt().toShort()
            }
        }
        return output
    }
}
