package com.aaryaharkare.voicekeyboard.formatter

data class FormatterResult(
    val formattedText: String,
    val mode: FormatterMode,
    val debugTrace: List<String> = emptyList(),
    val modelKey: String? = null,
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
    val generationMs: Long? = null,
)
