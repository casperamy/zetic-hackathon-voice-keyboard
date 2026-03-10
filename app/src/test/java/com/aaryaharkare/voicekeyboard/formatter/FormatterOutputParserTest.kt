package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatterOutputParserTest {
    @Test
    fun parse_validPayload_returnsParsedFields() {
        val payload =
            """
            {
              "has_list": true,
              "list_type": "bullet",
              "needs_formatting": true,
              "needs_correction": false,
              "suspected_word_fixes": ["teh -> the"],
              "formatted_text": "1. Apples\n2. Bananas",
              "confidence": 0.88
            }
            """.trimIndent()

        val parsed = FormatterOutputParser.parse(payload)

        assertNotNull(parsed)
        assertTrue(parsed!!.hasList)
        assertEquals("bullet", parsed.listType)
        assertEquals(0.88, parsed.confidence, 0.0001)
        assertEquals("1. Apples\n2. Bananas", parsed.formattedText)
    }

    @Test
    fun parse_withMarkdownFence_extractsJsonObject() {
        val payload =
            """
            ```json
            {"has_list":false,"list_type":"","needs_formatting":true,"needs_correction":true,"suspected_word_fixes":[],"formatted_text":"Hello, world.","confidence":91}
            ```
            """.trimIndent()

        val parsed = FormatterOutputParser.parse(payload)

        assertNotNull(parsed)
        assertEquals(0.91, parsed!!.confidence, 0.0001)
        assertEquals("Hello, world.", parsed.formattedText)
    }

    @Test
    fun parse_missingFormattedText_returnsNull() {
        val payload =
            """
            {"has_list":false,"confidence":0.9}
            """.trimIndent()

        assertNull(FormatterOutputParser.parse(payload))
    }

    @Test
    fun parse_malformedJson_returnsNull() {
        val malformed = """{"has_list":true,"formatted_text":"hello""""
        assertNull(FormatterOutputParser.parse(malformed))
    }
}
