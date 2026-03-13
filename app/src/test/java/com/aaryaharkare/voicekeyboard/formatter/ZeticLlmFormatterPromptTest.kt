package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertTrue
import org.junit.Test

class ZeticLlmFormatterPromptTest {

    @Test
    fun `warmup prompt is list planning prompt`() {
        val prompt = ZeticLlmFormatter.buildWarmupPrompt("Send it to jane.doe@example.com")

        assertTrue(prompt.contains("only formatting pass"))
        assertTrue(prompt.contains("<input_text>\nSend it to jane.doe@example.com"))
    }

    @Test
    fun `list planning prompt includes strict list schema`() {
        val prompt =
            ZeticLlmFormatter.buildListPlanningPrompt(
                correctedText = "There are four things that matter. Name, place, animal, and thing.",
                protectedSpans = emptyList(),
                fieldContext = FormatterFieldContext(inputType = 0, isMultiline = true),
            )

        assertTrue(prompt.contains("only formatting pass"))
        assertTrue(prompt.contains("\"list_plans\""))
        assertTrue(prompt.contains("Always return exactly one JSON object"))
        assertTrue(prompt.contains("must include only these fields"))
        assertTrue(prompt.contains("\"items\" must contain at least 2 strings"))
        assertTrue(prompt.contains("The most important things are name, place, animal, thing, and feelings."))
    }
}
