package com.aaryaharkare.voicekeyboard.formatter

import android.view.inputmethod.EditorInfo

interface FormatterEngine {
    suspend fun format(
        rawText: String,
        fieldContext: FormatterFieldContext,
    ): FormatterResult
}

data class FormatterFieldContext(
    val inputType: Int,
    val isMultiline: Boolean,
) {
    companion object {
        fun fromInputType(inputType: Int): FormatterFieldContext {
            return FormatterFieldContext(
                inputType = inputType,
                isMultiline = (inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0,
            )
        }
    }
}

data class FormatterResult(
    val finalText: String,
    val confidence: Double,
    val rawText: String,
    val formattedText: String,
    val changesApplied: Boolean,
    val debugReason: String,
    val cleanupPassMs: Long = 0L,
    val listPassMs: Long = 0L,
    val cleanupRawOutput: String = "",
    val listRawOutput: String = "",
    val cleanupText: String = "",
)
