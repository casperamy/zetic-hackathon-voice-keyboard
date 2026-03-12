package com.aaryaharkare.voicekeyboard.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperAudioPreprocessorTest {

    @Test
    fun `trims long trailing silence but keeps a short safety tail`() {
        val voiced = FloatArray(16_000) { 0.12f }
        val silence = FloatArray(12_800)
        val audio = voiced + silence

        val trimmed = WhisperAudioPreprocessor.trimTrailingSilence(audio)

        assertTrue(trimmed.size < audio.size)
        assertEquals(19_200, trimmed.size)
    }

    @Test
    fun `does not trim short trailing silence`() {
        val voiced = FloatArray(16_000) { 0.12f }
        val silence = FloatArray(3_200)
        val audio = voiced + silence

        val trimmed = WhisperAudioPreprocessor.trimTrailingSilence(audio)

        assertEquals(audio.size, trimmed.size)
    }

    @Test
    fun `keeps interior pauses untouched`() {
        val voicedStart = FloatArray(8_000) { 0.1f }
        val interiorSilence = FloatArray(8_000)
        val voicedEnd = FloatArray(8_000) { 0.1f }
        val audio = voicedStart + interiorSilence + voicedEnd

        val trimmed = WhisperAudioPreprocessor.trimTrailingSilence(audio)

        assertEquals(audio.size, trimmed.size)
    }
}
