package com.aaryaharkare.voicekeyboard.formatter

import org.json.JSONArray
import org.json.JSONObject

internal data class CleanupPassPayload(
    val cleanedText: String,
    val removedSegments: List<String>,
    val confidence: Double,
)

internal data class CorrectionPassPayload(
    val correctedText: String,
    val replacements: List<Replacement>,
    val confidence: Double,
)

internal data class Replacement(
    val from: String,
    val to: String,
    val reason: String? = null,
)

internal data class ListPlanPayload(
    val plans: List<ListPlan>,
    val confidence: Double,
)

internal object FormatterJsonParser {
    fun parseCleanup(rawOutput: String): CleanupPassPayload? {
        val json = extractJsonObject(rawOutput) ?: return null
        return runCatching {
            val payload = JSONObject(json)
            val cleanedText = payload.optString("cleaned_text", "").trim()
            if (cleanedText.isEmpty()) {
                return@runCatching null
            }

            CleanupPassPayload(
                cleanedText = cleanedText,
                removedSegments = parseStringArray(payload.optJSONArray("removed_segments")),
                confidence = normalizeConfidence(payload.opt("confidence")),
            )
        }.getOrNull()
    }

    fun parseCorrection(rawOutput: String): CorrectionPassPayload? {
        val json = extractJsonObject(rawOutput) ?: return null
        return runCatching {
            val payload = JSONObject(json)
            val correctedText = payload.optString("corrected_text", "").trim()
            if (correctedText.isEmpty()) {
                return@runCatching null
            }

            CorrectionPassPayload(
                correctedText = correctedText,
                replacements = parseReplacements(payload.optJSONArray("replacements")),
                confidence = normalizeConfidence(payload.opt("confidence")),
            )
        }.getOrNull()
    }

    fun parseListPlanning(rawOutput: String): ListPlanPayload? {
        val json = extractJsonObject(rawOutput) ?: return null
        return runCatching {
            val payload = JSONObject(json)
            ListPlanPayload(
                plans = parseListPlans(payload.optJSONArray("list_plans")),
                confidence = normalizeConfidence(payload.opt("confidence")),
            )
        }.getOrNull()
    }

    internal fun extractJsonObject(rawOutput: String): String? {
        val trimmed = rawOutput.trim()
        if (trimmed.isEmpty()) return null

        val firstBrace = trimmed.indexOf('{')
        if (firstBrace == -1) return null

        var depth = 0
        var endIndex = -1
        for (index in firstBrace until trimmed.length) {
            when (trimmed[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        endIndex = index
                        break
                    }
                }
            }
        }

        if (endIndex <= firstBrace) return null
        return trimmed.substring(firstBrace, endIndex + 1)
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index, "").trim()
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }
    }

    private fun parseReplacements(array: JSONArray?): List<Replacement> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val value = array.opt(index)
                when (value) {
                    is JSONObject -> {
                        val from = value.optString("from", "").trim()
                        val to = value.optString("to", "").trim()
                        val reason = value.optString("reason", "").trim().ifEmpty { null }
                        if (from.isNotEmpty() && to.isNotEmpty()) {
                            add(Replacement(from = from, to = to, reason = reason))
                        }
                    }
                    is String -> {
                        val candidate = value.trim()
                        val parts = candidate.split("->").map { it.trim() }
                        if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                            add(Replacement(from = parts[0], to = parts[1]))
                        }
                    }
                }
            }
        }
    }

    private fun parseListPlans(array: JSONArray?): List<ListPlan> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val sourceSpanText = item.optString("source_span_text", "").trim()
                val introText = item.optString("intro_text", "").trim()
                val items = parseStringArray(item.optJSONArray("items"))
                val listType = item.optString("list_type", "").trim().toListType() ?: continue
                val evidenceType =
                    item.optString("evidence_type", "").trim().toEvidenceType()
                        ?: EvidenceType.STANDALONE_ITEM_SEQUENCE
                if (sourceSpanText.isEmpty() || items.size < 2) continue

                add(
                    ListPlan(
                        sourceSpanText = sourceSpanText,
                        introText = introText,
                        items = items,
                        listType = listType,
                        confidence = normalizeConfidence(item.opt("confidence")),
                        evidenceType = evidenceType,
                        orderedReason = item.optString("ordered_reason", "").trim().ifEmpty { null },
                    ),
                )
            }
        }
    }

    private fun normalizeConfidence(value: Any?): Double {
        val parsed =
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            } ?: 0.0

        val normalized = if (parsed > 1.0 && parsed <= 100.0) parsed / 100.0 else parsed
        return normalized.coerceIn(0.0, 1.0)
    }

    private fun String.toListType(): ListType? {
        return when (uppercase()) {
            "ORDERED" -> ListType.ORDERED
            "UNORDERED" -> ListType.UNORDERED
            else -> null
        }
    }

    private fun String.toEvidenceType(): EvidenceType? {
        return when (uppercase()) {
            "EXPLICIT_MARKERS" -> EvidenceType.EXPLICIT_MARKERS
            "INLINE_NUMBERED_SEQUENCE" -> EvidenceType.INLINE_NUMBERED_SEQUENCE
            "INTRO_PLUS_ITEMS" -> EvidenceType.INTRO_PLUS_ITEMS
            "CROSS_SENTENCE_INTRO" -> EvidenceType.CROSS_SENTENCE_INTRO
            "SENTENCE_RUN" -> EvidenceType.SENTENCE_RUN
            "STANDALONE_ITEM_SEQUENCE" -> EvidenceType.STANDALONE_ITEM_SEQUENCE
            else -> null
        }
    }
}
