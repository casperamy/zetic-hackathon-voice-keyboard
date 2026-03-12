package com.aaryaharkare.voicekeyboard.whisper

import kotlin.math.sqrt

internal object WhisperAudioPreprocessor {

    fun trimTrailingSilence(
        audio: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ): FloatArray {
        if (audio.isEmpty()) return audio

        val windowSize = (sampleRate * WINDOW_MS) / 1_000
        val minTrailingSilence = (sampleRate * MIN_TRAILING_SILENCE_MS) / 1_000
        val keepTail = (sampleRate * KEEP_TAIL_MS) / 1_000

        var silenceSamples = 0
        var trimStart = audio.size
        var end = audio.size

        while (end > 0) {
            val start = (end - windowSize).coerceAtLeast(0)
            if (windowRms(audio, start, end) >= TRAILING_SILENCE_RMS_THRESHOLD) {
                break
            }
            silenceSamples += end - start
            trimStart = start
            end = start
        }

        if (silenceSamples < minTrailingSilence) return audio

        val trimmedLength = (trimStart + keepTail).coerceIn(0, audio.size)
        return audio.copyOf(trimmedLength)
    }

    private fun windowRms(
        audio: FloatArray,
        start: Int,
        end: Int,
    ): Double {
        if (end <= start) return 0.0

        var sumSquares = 0.0
        var count = 0
        var index = start
        while (index < end) {
            val sample = audio[index].toDouble()
            sumSquares += sample * sample
            count += 1
            index += 1
        }

        if (count == 0) return 0.0
        return sqrt(sumSquares / count.toDouble())
    }

    private const val DEFAULT_SAMPLE_RATE = 16_000
    private const val WINDOW_MS = 20
    private const val MIN_TRAILING_SILENCE_MS = 400
    private const val KEEP_TAIL_MS = 200
    private const val TRAILING_SILENCE_RMS_THRESHOLD = 0.0085
}
