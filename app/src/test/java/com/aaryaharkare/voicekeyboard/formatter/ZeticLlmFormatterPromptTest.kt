package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertTrue
import org.junit.Test

class ZeticLlmFormatterPromptTest {

    @Test
    fun `cleanup prompt includes protected spans and raw text`() {
        val prompt =
            ZeticLlmFormatter.buildCleanupPrompt(
                rawText = "Send it to jane.doe@example.com",
                protectedSpans =
                    listOf(
                        ProtectedSpan(
                            label = "email",
                            text = "jane.doe@example.com",
                            startIndex = 11,
                            endIndex = 31,
                        ),
                    ),
            )

        assertTrue(prompt.contains("pass 1"))
        assertTrue(prompt.contains("- email: jane.doe@example.com"))
        assertTrue(prompt.contains("<input_text>\nSend it to jane.doe@example.com"))
    }

    @Test
    fun `correction prompt includes asr hints`() {
        val prompt =
            ZeticLlmFormatter.buildCorrectionPrompt(
                cleanedText = "freeze the beach mark",
                protectedSpans = emptyList(),
                fieldContext = FormatterFieldContext(inputType = 0, isMultiline = false),
                asrHints = AsrHints(alternativePhrases = listOf("benchmark", "bench mark")),
            )

        assertTrue(prompt.contains("pass 2"))
        assertTrue(prompt.contains("- benchmark"))
        assertTrue(prompt.contains("\"replacements\""))
    }

    @Test
    fun `list planning prompt includes strict list schema`() {
        val prompt =
            ZeticLlmFormatter.buildListPlanningPrompt(
                correctedText = "There are four things that matter. Name, place, animal, and thing.",
                protectedSpans = emptyList(),
                fieldContext = FormatterFieldContext(inputType = 0, isMultiline = true),
            )

        assertTrue(prompt.contains("pass 3"))
        assertTrue(prompt.contains("\"list_plans\""))
        assertTrue(prompt.contains("If uncertain, return no list plans."))
    }
}
