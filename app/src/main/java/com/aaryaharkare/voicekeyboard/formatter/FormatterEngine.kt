package com.aaryaharkare.voicekeyboard.formatter

import android.view.inputmethod.EditorInfo

interface FormatterEngine {
    suspend fun format(
        text: String,
        fieldContext: FormatterFieldContext,
        asrHints: AsrHints? = null,
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

data class AsrHints(
    val alternativePhrases: List<String> = emptyList(),
)
