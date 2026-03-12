package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatterDeterministicGuardrailsTest {

    @Test
    fun `list surface detector finds explicit numbered sequence`() {
        val signal = ListSurfaceDetector.detect("1. Freeze scope. 2. Rerun QA. 3. Ship the build.")

        assertTrue(signal.shouldRun)
        assertEquals("inline_numbering", signal.reason)
    }

    @Test
    fun `validator rejects clause chain presented as list`() {
        val payload =
            ListPlanPayload(
                plans =
                    listOf(
                        ListPlan(
                            sourceSpanText = "The build was green, the dashboard was red, and nobody trusted either one.",
                            introText = "",
                            items = listOf("The build was green", "the dashboard was red", "nobody trusted either one"),
                            listType = ListType.UNORDERED,
                            confidence = 0.93,
                            evidenceType = EvidenceType.STANDALONE_ITEM_SEQUENCE,
                            orderedReason = null,
                        ),
                    ),
                confidence = 0.93,
            )

        val resolution =
            FormatterPassValidator.resolveListPlans(
                text = "The build was green, the dashboard was red, and nobody trusted either one.",
                payload = payload,
                protectedSpans = emptyList(),
            )

        assertTrue(resolution.acceptedPlans.isEmpty())
    }

    @Test
    fun `renderer preserves surrounding prose and formats only the list block`() {
        val text = "We tested the build yesterday. Here are the next steps. Freeze scope. Ship the build. Send notes."
        val span = "Here are the next steps. Freeze scope. Ship the build. Send notes."
        val rendered =
            ListPlanRenderer.render(
                text = text,
                plans =
                    listOf(
                        ListPlan(
                            sourceSpanText = span,
                            introText = "Here are the next steps",
                            items = listOf("Freeze scope.", "Ship the build.", "Send notes."),
                            listType = ListType.UNORDERED,
                            confidence = 0.90,
                            evidenceType = EvidenceType.SENTENCE_RUN,
                            orderedReason = null,
                            startIndex = text.indexOf(span),
                            endIndex = text.indexOf(span) + span.length,
                        ),
                    ),
            )

        assertEquals(
            """
            We tested the build yesterday.

            Here are the next steps:
            • Freeze scope.
            • Ship the build.
            • Send notes.
            """.trimIndent(),
            rendered,
        )
    }

    @Test
    fun `cleanup validation rejects protected span edits`() {
        val result =
            FormatterPassValidator.validateCleanup(
                rawText = "Send it to jane.doe@example.com",
                payload =
                    CleanupPassPayload(
                        cleanedText = "Send it to Jane Doe",
                        removedSegments = listOf("jane.doe@example.com"),
                        confidence = 0.95,
                    ),
                protectedSpans = ProtectedSpanDetector.detect("Send it to jane.doe@example.com"),
            )

        assertFalse(result.accepted)
        assertEquals("cleanup_touched_protected_span", result.reason)
    }
}
