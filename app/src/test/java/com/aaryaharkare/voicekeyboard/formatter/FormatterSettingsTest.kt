package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatterSettingsTest {

    @Test
    fun `defaults to deterministic mode when nothing is stored`() {
        val settings = FormatterSettings(FakeFormatterModeStore())

        assertEquals(FormatterMode.DETERMINISTIC, settings.getMode())
    }

    @Test
    fun `persists selected formatter mode`() {
        val store = FakeFormatterModeStore()
        val settings = FormatterSettings(store)

        settings.setMode(FormatterMode.ZETIC_LLM)

        assertEquals("zetic_llm", store.value)
        assertEquals(FormatterMode.ZETIC_LLM, settings.getMode())
    }

    private class FakeFormatterModeStore(
        var value: String? = null,
    ) : FormatterModeStore {
        override fun read(): String? = value

        override fun write(value: String) {
            this.value = value
        }
    }
}
