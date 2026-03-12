package com.aaryaharkare.voicekeyboard.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhisperDecodePolicyTest {

    @Test
    fun `compute decode cap clamps to minimum for short audio`() {
        assertEquals(24, WhisperDecodePolicy.computeDecodeCap(sampleCount = 2_000))
    }

    @Test
    fun `compute decode cap scales with audio length`() {
        assertEquals(48, WhisperDecodePolicy.computeDecodeCap(sampleCount = 160_000))
    }

    @Test
    fun `compute decode cap clamps to maximum for long audio`() {
        assertEquals(96, WhisperDecodePolicy.computeDecodeCap(sampleCount = 480_000))
    }

    @Test
    fun `repeat one stop fires on three identical trailing tokens`() {
        val generated = intArrayOf(12, 12)
        assertEquals(
            WhisperDecodePolicy.StopReason.REPEAT_1,
            WhisperDecodePolicy.repetitionStopReason(generated, generatedCount = 2, nextToken = 12),
        )
    }

    @Test
    fun `repeat two stop fires on repeated two token loop`() {
        val generated = intArrayOf(4, 7, 4, 7, 4)
        assertEquals(
            WhisperDecodePolicy.StopReason.REPEAT_2,
            WhisperDecodePolicy.repetitionStopReason(generated, generatedCount = 5, nextToken = 7),
        )
    }

    @Test
    fun `repeat three stop fires on repeated three token loop`() {
        val generated = intArrayOf(3, 5, 8, 3, 5)
        assertEquals(
            WhisperDecodePolicy.StopReason.REPEAT_3,
            WhisperDecodePolicy.repetitionStopReason(generated, generatedCount = 5, nextToken = 8),
        )
    }

    @Test
    fun `returns null when no repetition stop applies`() {
        val generated = intArrayOf(1, 2, 3, 4, 5)
        assertNull(WhisperDecodePolicy.repetitionStopReason(generated, generatedCount = 5, nextToken = 6))
    }
}
