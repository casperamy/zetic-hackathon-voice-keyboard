package com.aaryaharkare.voicekeyboard.whisper

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object WhisperDecodePolicy {

    enum class StopReason {
        EOS,
        CAP,
        REPEAT_1,
        REPEAT_2,
        REPEAT_3,
    }

    fun computeDecodeCap(
        sampleCount: Int,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ): Int {
        val seconds = sampleCount.coerceAtLeast(0).toDouble() / sampleRate.toDouble()
        val adaptiveCap = ceil(seconds * TOKENS_PER_SECOND).toInt() + TOKEN_HEADROOM
        return min(MAX_DECODE_CAP, max(MIN_DECODE_CAP, adaptiveCap))
    }

    fun repetitionStopReason(
        generated: IntArray,
        generatedCount: Int,
        nextToken: Int,
    ): StopReason? {
        if (generatedCount >= 2) {
            val prev = generated[generatedCount - 1]
            val prev2 = generated[generatedCount - 2]
            if (nextToken == prev && prev == prev2) {
                return StopReason.REPEAT_1
            }
        }

        if (generatedCount >= 5) {
            val a0 = generated[generatedCount - 5]
            val b0 = generated[generatedCount - 4]
            val a1 = generated[generatedCount - 3]
            val b1 = generated[generatedCount - 2]
            val a2 = generated[generatedCount - 1]
            if (a0 == a1 && a1 == a2 && b0 == b1 && nextToken == b0) {
                return StopReason.REPEAT_2
            }
        }

        if (generatedCount >= 5) {
            val a0 = generated[generatedCount - 5]
            val b0 = generated[generatedCount - 4]
            val c0 = generated[generatedCount - 3]
            val a1 = generated[generatedCount - 2]
            val b1 = generated[generatedCount - 1]
            if (a0 == a1 && b0 == b1 && nextToken == c0) {
                return StopReason.REPEAT_3
            }
        }

        return null
    }

    private const val DEFAULT_SAMPLE_RATE = 16_000
    private const val TOKENS_PER_SECOND = 4.0
    private const val TOKEN_HEADROOM = 8
    private const val MIN_DECODE_CAP = 24
    private const val MAX_DECODE_CAP = 96
}
