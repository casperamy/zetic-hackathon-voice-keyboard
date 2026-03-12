package com.aaryaharkare.voicekeyboard.formatter

enum class FormatterMode(
    private val storageValue: String,
) {
    DETERMINISTIC("deterministic"),
    ZETIC_LLM("zetic_llm"),
    ;

    fun toStorageValue(): String = storageValue

    companion object {
        fun fromStorageValue(value: String?): FormatterMode {
            return entries.firstOrNull { it.storageValue == value } ?: DETERMINISTIC
        }
    }
}
