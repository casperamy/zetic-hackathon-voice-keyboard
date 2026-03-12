package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertEquals
import org.junit.Test

class DeterministicListFormatterTest {

    private val formatter = DeterministicListFormatter()

    @Test
    fun `formats a sentence with an inferred list`() {
        val input =
            "The most important thing about this app is its design, its development, its business strategy, its marketing, and its sales."

        val formatted = formatter.format(input)

        assertEquals(
            """
            The most important thing about this app is:
            • its design
            • its development
            • its business strategy
            • its marketing
            • its sales
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun `formats only the list sentence in a longer paragraph`() {
        val input =
            "The priorities are design, development, testing, and launch readiness. The budget is still under review."

        val formatted = formatter.format(input)

        assertEquals(
            """
            The priorities are:
            • design
            • development
            • testing
            • launch readiness

            The budget is still under review.
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun `keeps ordinary comma sentence unchanged`() {
        val input = "I went to the store, bought milk, and came home."

        assertEquals(input, formatter.format(input))
    }

    @Test
    fun `requires at least three list items`() {
        val input = "The app needs design and development."

        assertEquals(input, formatter.format(input))
    }
}
