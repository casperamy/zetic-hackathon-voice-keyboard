package com.aaryaharkare.voicekeyboard.formatter

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatterDecisionPolicyTest {
    @Test
    fun shouldAttemptFormatting_textFieldEnabled_returnsTrue() {
        val inputType = EditorInfo.TYPE_CLASS_TEXT
        assertTrue(FormatterDecisionPolicy.shouldAttemptFormatting(true, inputType))
    }

    @Test
    fun shouldAttemptFormatting_emailVariation_returnsFalse() {
        val inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertFalse(FormatterDecisionPolicy.shouldAttemptFormatting(true, inputType))
    }

    @Test
    fun shouldAttemptFormatting_password_returnsFalse() {
        val inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        assertFalse(FormatterDecisionPolicy.shouldAttemptFormatting(true, inputType))
    }

    @Test
    fun shouldAttemptFormatting_numberField_returnsFalse() {
        val inputType = EditorInfo.TYPE_CLASS_NUMBER
        assertFalse(FormatterDecisionPolicy.shouldAttemptFormatting(true, inputType))
    }

    @Test
    fun chooseFinalText_highConfidence_usesFormattedText() {
        val decision =
            FormatterDecisionPolicy.chooseFinalText(
                rawText = "hello world",
                formattedText = "Hello world.",
                confidence = 0.95,
                threshold = 0.72,
            )

        assertEquals("Hello world.", decision.finalText)
        assertTrue(decision.changesApplied)
        assertEquals("formatter_applied", decision.reason)
    }

    @Test
    fun chooseFinalText_lowConfidence_fallsBackToRaw() {
        val decision =
            FormatterDecisionPolicy.chooseFinalText(
                rawText = "hello world",
                formattedText = "Hello world.",
                confidence = 0.35,
                threshold = 0.72,
            )

        assertEquals("hello world", decision.finalText)
        assertFalse(decision.changesApplied)
        assertEquals("low_confidence_fallback", decision.reason)
    }

    @Test
    fun chooseFinalText_listFormatting_allowsLowerConfidenceWhenBulleted() {
        val decision =
            FormatterDecisionPolicy.chooseFinalText(
                rawText = "plants animals things species",
                formattedText = "- plants\n- animals\n- things\n- species",
                confidence = 0.50,
                hasList = true,
                needsFormatting = true,
            )

        assertEquals("- plants\n- animals\n- things\n- species", decision.finalText)
        assertTrue(decision.changesApplied)
        assertEquals("formatter_applied", decision.reason)
    }

    @Test
    fun chooseFinalText_listButNotBulleted_stillUsesDefaultThreshold() {
        val decision =
            FormatterDecisionPolicy.chooseFinalText(
                rawText = "plants animals things species",
                formattedText = "Plants, animals, things, species.",
                confidence = 0.50,
                hasList = true,
                needsFormatting = true,
            )

        assertEquals("plants animals things species", decision.finalText)
        assertFalse(decision.changesApplied)
        assertEquals("low_confidence_fallback", decision.reason)
    }

    @Test
    fun chooseFinalText_bulletedOutput_usesListThresholdEvenWithoutHasListFlag() {
        val decision =
            FormatterDecisionPolicy.chooseFinalText(
                rawText = "plants animals things species",
                formattedText = "- plants\n- animals\n- things\n- species",
                confidence = 0.50,
                hasList = false,
                needsFormatting = false,
            )

        assertEquals("- plants\n- animals\n- things\n- species", decision.finalText)
        assertTrue(decision.changesApplied)
        assertEquals("formatter_applied", decision.reason)
    }
}
