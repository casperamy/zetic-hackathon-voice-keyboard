package com.aaryaharkare.voicekeyboard.formatter

class FormatterEngineSelector(
    private val deterministicEngine: FormatterEngine,
    private val zeticLlmEngine: FormatterEngine,
) {
    fun select(mode: FormatterMode): FormatterEngine {
        return when (mode) {
            FormatterMode.DETERMINISTIC -> deterministicEngine
            FormatterMode.ZETIC_LLM -> zeticLlmEngine
        }
    }
}
