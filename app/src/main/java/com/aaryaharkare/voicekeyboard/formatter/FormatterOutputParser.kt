package com.aaryaharkare.voicekeyboard.formatter

import org.json.JSONArray
import org.json.JSONObject

data class ParsedFormatterPayload(
    val hasList: Boolean,
    val listType: String?,
    val needsFormatting: Boolean,
    val needsCorrection: Boolean,
    val suspectedWordFixes: List<String>,
    val formattedText: String,
    val confidence: Double,
)

object FormatterOutputParser {
    fun parse(rawOutput: String): ParsedFormatterPayload? {
        val jsonPayload = extractJsonObject(rawOutput) ?: return null
        return runCatching {
            val parsed = JSONObject(jsonPayload)
            val formattedText = parsed.optString("formatted_text", "").trim()
            if (formattedText.isEmpty()) {
                return null
            }
            val listType =
                if (parsed.has("list_type") && !parsed.isNull("list_type")) {
                    parsed.optString("list_type", "").trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }

            ParsedFormatterPayload(
                hasList = parsed.optBoolean("has_list", false),
                listType = listType,
                needsFormatting = parsed.optBoolean("needs_formatting", false),
                needsCorrection = parsed.optBoolean("needs_correction", false),
                suspectedWordFixes = parseWordFixes(parsed.optJSONArray("suspected_word_fixes")),
                formattedText = formattedText,
                confidence = normalizeConfidence(parsed.opt("confidence")),
            )
        }.getOrNull()
    }

    private fun parseWordFixes(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            var i = 0
            while (i < array.length()) {
                add(array.opt(i).toString())
                i += 1
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

        val normalized =
            if (parsed > 1.0 && parsed <= 100.0) {
                parsed / 100.0
            } else {
                parsed
            }
        return normalized.coerceIn(0.0, 1.0)
    }

    private fun extractJsonObject(rawOutput: String): String? {
        val trimmed = rawOutput.trim()
        if (trimmed.isEmpty()) return null

        val firstBrace = trimmed.indexOf('{')
        if (firstBrace == -1) return null

        var depth = 0
        var endIndex = -1
        for (i in firstBrace until trimmed.length) {
            when (trimmed[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        endIndex = i
                        break
                    }
                }
            }
        }

        if (endIndex <= firstBrace) return null
        return trimmed.substring(firstBrace, endIndex + 1)
    }
}
