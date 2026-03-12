package com.aaryaharkare.voicekeyboard.formatter

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Test

class FormatterEngineSelectorTest {

    @Test
    fun `selects deterministic engine when toggle is off`() {
        val deterministic = FakeFormatterEngine(FormatterMode.DETERMINISTIC)
        val zetic = FakeFormatterEngine(FormatterMode.ZETIC_LLM)
        val selector = FormatterEngineSelector(deterministic, zetic)

        assertSame(deterministic, selector.select(FormatterMode.DETERMINISTIC))
    }

    @Test
    fun `selects zetic engine when toggle is on`() {
        val deterministic = FakeFormatterEngine(FormatterMode.DETERMINISTIC)
        val zetic = FakeFormatterEngine(FormatterMode.ZETIC_LLM)
        val selector = FormatterEngineSelector(deterministic, zetic)

        assertSame(zetic, selector.select(FormatterMode.ZETIC_LLM))
    }

    private class FakeFormatterEngine(
        private val mode: FormatterMode,
    ) : FormatterEngine {
        override suspend fun format(text: String): FormatterResult {
            return FormatterResult(
                formattedText = text,
                mode = mode,
            )
        }
    }
}
