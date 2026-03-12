package com.aaryaharkare.voicekeyboard.formatter

interface FormatterEngine {
    suspend fun format(text: String): FormatterResult
}
