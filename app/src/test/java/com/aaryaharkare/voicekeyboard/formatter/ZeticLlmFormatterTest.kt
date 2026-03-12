package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeticLlmFormatterTest {

    @Test
    fun `builds prompt with system instructions and raw text`() {
        val prompt = ZeticLlmFormatter.buildPrompt("Buy milk eggs and bread")

        assertTrue(prompt.contains("You format raw Whisper transcription for a keyboard."))
        assertTrue(prompt.contains("Raw text:\nBuy milk eggs and bread"))
    }

    @Test
    fun `trims generated output before returning it`() {
        val output = ZeticLlmFormatter.validateOutput("  • Buy milk\n• Buy eggs  ")

        assertEquals("• Buy milk\n• Buy eggs", output)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects blank generated output`() {
        ZeticLlmFormatter.validateOutput("   ")
    }
}
