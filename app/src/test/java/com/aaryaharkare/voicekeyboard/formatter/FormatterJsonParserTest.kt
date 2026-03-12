package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatterJsonParserTest {

    @Test
    fun `parse correction payload extracts replacements`() {
        val payload =
            """
            {
              "corrected_text": "freeze the benchmark today",
              "replacements": [
                {"from": "beach mark", "to": "benchmark", "reason": "contextual_asr_fix"}
              ],
              "confidence": 0.81
            }
            """.trimIndent()

        val parsed = FormatterJsonParser.parseCorrection(payload)

        assertNotNull(parsed)
        assertEquals("freeze the benchmark today", parsed!!.correctedText)
        assertEquals(1, parsed.replacements.size)
        assertEquals("beach mark", parsed.replacements.single().from)
    }

    @Test
    fun `parse cleanup payload supports fenced json`() {
        val payload =
            """
            ```json
            {"cleaned_text":"freeze the benchmark today","removed_segments":["um"],"confidence":91}
            ```
            """.trimIndent()

        val parsed = FormatterJsonParser.parseCleanup(payload)

        assertNotNull(parsed)
        assertEquals(0.91, parsed!!.confidence, 0.0001)
        assertEquals(listOf("um"), parsed.removedSegments)
    }

    @Test
    fun `parse list planning payload extracts list plans`() {
        val payload =
            """
            {
              "list_plans": [
                {
                  "source_span_text": "There are four things that matter. Name, place, animal, and thing.",
                  "intro_text": "There are four things that matter",
                  "items": ["Name", "place", "animal", "thing"],
                  "list_type": "UNORDERED",
                  "evidence_type": "CROSS_SENTENCE_INTRO",
                  "ordered_reason": "",
                  "confidence": 0.88
                }
              ],
              "confidence": 0.88
            }
            """.trimIndent()

        val parsed = FormatterJsonParser.parseListPlanning(payload)

        assertNotNull(parsed)
        assertEquals(1, parsed!!.plans.size)
        assertEquals(EvidenceType.CROSS_SENTENCE_INTRO, parsed.plans.single().evidenceType)
        assertTrue(parsed.plans.single().items.contains("animal"))
    }
}
