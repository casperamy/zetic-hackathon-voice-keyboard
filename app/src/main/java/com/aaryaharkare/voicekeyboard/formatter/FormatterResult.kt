package com.aaryaharkare.voicekeyboard.formatter

data class FormatterResult(
    val formattedText: String,
    val mode: FormatterMode,
    val debugTrace: List<String> = emptyList(),
    val modelKey: String? = null,
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
    val generationMs: Long? = null,
    val rawText: String = "",
    val pass1Text: String = "",
    val pass2Text: String = "",
    val listPlans: List<ListPlan> = emptyList(),
    val passMetrics: List<FormatterPassMetrics> = emptyList(),
    val fallbackReason: String? = null,
)

data class FormatterPassMetrics(
    val name: String,
    val accepted: Boolean,
    val reason: String,
    val confidence: Double?,
    val promptTokens: Int,
    val generatedTokens: Int,
    val generationMs: Long,
)

data class ListPlan(
    val sourceSpanText: String,
    val introText: String,
    val items: List<String>,
    val listType: ListType,
    val confidence: Double,
    val evidenceType: EvidenceType,
    val orderedReason: String?,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)

enum class ListType {
    ORDERED,
    UNORDERED,
}

enum class EvidenceType {
    EXPLICIT_MARKERS,
    INLINE_NUMBERED_SEQUENCE,
    INTRO_PLUS_ITEMS,
    CROSS_SENTENCE_INTRO,
    SENTENCE_RUN,
    STANDALONE_ITEM_SEQUENCE,
}
