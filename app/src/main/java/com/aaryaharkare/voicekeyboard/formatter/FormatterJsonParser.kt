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
            val payloadConfidence = normalizeConfidence(payload.opt("confidence"))
            ListPlanPayload(
                plans = parseListPlans(payload.optJSONArray("list_plans"), payloadConfidence),
                confidence = payloadConfidence,
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

    private fun parseListPlans(
        array: JSONArray?,
        payloadConfidence: Double,
    ): List<ListPlan> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val sourceSpanText = item.optString("source_span_text", "").trim()
                val introText = item.optString("intro_text", "").trim()
                val items =
                    parseStringArray(item.optJSONArray("items"))
                        .ifEmpty { inferItems(sourceSpanText, introText) }
                val listType =
                    item.optString("list_type", "").trim().toListType()
                        ?: inferListType(sourceSpanText, introText, items)
                val evidenceType =
                    item.optString("evidence_type", "").trim().toEvidenceType()
                        ?: inferEvidenceType(sourceSpanText, introText, items)
                if (sourceSpanText.isEmpty() || items.size < 2 || listType == null) continue

                val planConfidence = normalizeConfidence(item.opt("confidence")).takeIf { it > 0.0 } ?: payloadConfidence

                add(
                    ListPlan(
                        sourceSpanText = sourceSpanText,
                        introText = introText,
                        items = items,
                        listType = listType,
                        confidence = planConfidence,
                        evidenceType = evidenceType,
                        orderedReason =
                            item.optString("ordered_reason", "").trim().ifEmpty {
                                inferOrderedReason(sourceSpanText, introText, items, listType)
                            },
                    ),
                )
            }
        }
    }

    private fun inferItems(
        sourceSpanText: String,
        introText: String,
    ): List<String> {
        if (sourceSpanText.isBlank()) return emptyList()

        val expectedCount = extractExpectedCount(sourceSpanText)
        val candidates = buildCandidates(sourceSpanText, introText)

        candidates.forEach { candidate ->
            splitExplicitMarkers(candidate).takeIf { it.size >= 2 }?.let { return it }

            val delimited = splitDelimitedItems(candidate)
            if (delimited.size >= 2) {
                maybeExpandItems(delimited, expectedCount)?.let { return it }
                return delimited
            }

            splitSentenceItems(candidate).takeIf { it.size >= 2 }?.let { return it }

            splitCountSizedTokenItems(candidate, expectedCount).takeIf { it.size >= 2 }?.let { return it }
        }

        return emptyList()
    }

    private fun buildCandidates(
        sourceSpanText: String,
        introText: String,
    ): List<String> {
        val candidates = linkedSetOf<String>()
        val trimmedSource = sourceSpanText.trim()
        val trimmedIntro = introText.trim()

        afterCountLeadRegex.find(trimmedSource)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { candidates += it }

        if (trimmedIntro.isNotEmpty() && trimmedSource.startsWith(trimmedIntro) && trimmedSource.length > trimmedIntro.length) {
            trimmedSource
                .removePrefix(trimmedIntro)
                .trimStart(' ', ':', ';', '-', '.', ',')
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { candidates += it }
        }

        val firstSentenceSplit = sentenceBoundaryRegex.split(trimmedSource, limit = 2)
        if (firstSentenceSplit.size == 2 && introCueRegex.containsMatchIn(firstSentenceSplit[0])) {
            firstSentenceSplit[1].trim().takeIf { it.isNotEmpty() }?.let { candidates += it }
        }

        listOf(':', ';').forEach { marker ->
            trimmedSource.substringAfter(marker, "").trim().takeIf { it.isNotEmpty() }?.let { candidates += it }
        }

        candidates += trimmedSource

        return candidates.toList()
    }

    private fun splitExplicitMarkers(candidate: String): List<String> {
        val inlineNumbered =
            inlineNumberedRegex.findAll(candidate)
                .map { it.groupValues[1].trim().trimEnd('.', ';', ',') }
                .filter { it.isNotEmpty() }
                .toList()
        if (inlineNumbered.size >= 2) return inlineNumbered

        val lines =
            candidate.lines()
                .mapNotNull { line ->
                    explicitLineRegex.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.trim()
                }
                .filter { it.isNotEmpty() }
        return if (lines.size >= 2) lines else emptyList()
    }

    private fun splitDelimitedItems(candidate: String): List<String> {
        val normalized = candidate.trim().trimEnd('.')
        if (!normalized.contains(',') && !delimiterWordRegex.containsMatchIn(normalized)) {
            return emptyList()
        }

        return normalized
            .split(delimitedSplitRegex)
            .map { it.trim().trimEnd('.', ';', ',') }
            .filter { it.isNotEmpty() }
    }

    private fun maybeExpandItems(
        items: List<String>,
        expectedCount: Int?,
    ): List<String>? {
        if (expectedCount == null || items.size >= expectedCount) return null

        val flattened =
            items.flatMap { item ->
                wordTokenRegex.findAll(item)
                    .map { it.value }
                    .filterNot(::isJoinWord)
                    .toList()
            }
        return flattened.takeIf { it.size == expectedCount }
    }

    private fun splitSentenceItems(candidate: String): List<String> {
        val sentences =
            sentenceBoundaryRegex.split(candidate.trim())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (sentences.size < 2) return emptyList()

        val shortSentences =
            sentences.filter { sentence ->
                val words = wordTokenRegex.findAll(sentence).count()
                words in 1..6
            }
        return if (shortSentences.size >= 2) shortSentences else emptyList()
    }

    private fun splitCountSizedTokenItems(
        candidate: String,
        expectedCount: Int?,
    ): List<String> {
        if (expectedCount == null) return emptyList()

        val tokens =
            wordTokenRegex.findAll(candidate)
                .map { it.value }
                .filterNot(::isJoinWord)
                .toList()

        return if (tokens.size == expectedCount) tokens else emptyList()
    }

    private fun inferListType(
        sourceSpanText: String,
        introText: String,
        items: List<String>,
    ): ListType? {
        if (items.size < 2) return null

        return if (
            orderedCueRegex.containsMatchIn(sourceSpanText) ||
            orderedCueRegex.containsMatchIn(introText) ||
            inlineNumberedRegex.findAll(sourceSpanText).count() >= 2
        ) {
            ListType.ORDERED
        } else {
            ListType.UNORDERED
        }
    }

    private fun inferEvidenceType(
        sourceSpanText: String,
        introText: String,
        items: List<String>,
    ): EvidenceType {
        if (explicitLineRegex.findAll(sourceSpanText).count() >= 2) return EvidenceType.EXPLICIT_MARKERS
        if (inlineNumberedRegex.findAll(sourceSpanText).count() >= 2) return EvidenceType.INLINE_NUMBERED_SEQUENCE
        if (sentenceBoundaryRegex.split(sourceSpanText.trim()).size >= 2 && introCueRegex.containsMatchIn(sourceSpanText)) {
            return EvidenceType.CROSS_SENTENCE_INTRO
        }
        if (introText.isNotBlank() || introCueRegex.containsMatchIn(sourceSpanText)) return EvidenceType.INTRO_PLUS_ITEMS
        if (items.size >= 2 && sentenceBoundaryRegex.split(sourceSpanText.trim()).size >= items.size) {
            return EvidenceType.SENTENCE_RUN
        }
        return EvidenceType.STANDALONE_ITEM_SEQUENCE
    }

    private fun inferOrderedReason(
        sourceSpanText: String,
        introText: String,
        items: List<String>,
        listType: ListType,
    ): String? {
        if (listType != ListType.ORDERED) return null
        return when {
            inlineNumberedRegex.findAll(sourceSpanText).count() >= 2 -> "explicit_numbering"
            orderedCueRegex.containsMatchIn(introText) -> introText
            orderedCueRegex.containsMatchIn(sourceSpanText) -> "ordered_cue"
            items.size >= 2 -> "sequence_inferred"
            else -> null
        }
    }

    private fun extractExpectedCount(text: String): Int? {
        val normalized = text.lowercase()
        if (!introCueRegex.containsMatchIn(normalized) && !countListCueRegex.containsMatchIn(normalized)) {
            return null
        }

        val match = countRegex.find(normalized) ?: return null
        val raw = match.value
        return raw.toIntOrNull() ?: numberWordToValue[raw]
    }

    private fun isJoinWord(token: String): Boolean {
        return token.equals("and", ignoreCase = true) || token.equals("or", ignoreCase = true)
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

    private val numberWordToValue =
        mapOf(
            "one" to 1,
            "two" to 2,
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9,
            "ten" to 10,
        )

    private val countRegex = Regex("""\b(?:\d+|one|two|three|four|five|six|seven|eight|nine|ten)\b""")
    private val introCueRegex =
        Regex(
            """\b(?:here are|there are|important thing|important things|main thing|main things|list|include|includes|contains|steps|points|reasons|tasks|things|items|priorities)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val countListCueRegex =
        Regex(
            """\b(?:\d+|one|two|three|four|five|six|seven|eight|nine|ten)\s+(?:main\s+|important\s+)?(?:things|items|steps|points|reasons|tasks|priorities)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val afterCountLeadRegex =
        Regex(
            """\b(?:is|are|was|were|include|includes|included|contain|contains|contained)\s+(?:\d+|one|two|three|four|five|six|seven|eight|nine|ten)(?:\s+(?:main|important|different|key))?(?:\s+(?:things|items|steps|points|reasons|tasks|priorities))?[.:;-]\s*(.+)$""",
            RegexOption.IGNORE_CASE,
        )
    private val sentenceBoundaryRegex = Regex("""(?<=[.!?])\s+""")
    private val explicitLineRegex = Regex("""^\s*(?:[-*•]|\d+[.)])\s+(.+)$""")
    private val explicitOrderedRegex = Regex("""(?:^|[\s:;,.!?])\d+[.)]\s+\S""")
    private val inlineNumberedRegex = Regex("""\d+[.)]\s+(.+?)(?=(?:\s+\d+[.)]\s+)|$)""")
    private val delimiterWordRegex = Regex("""\b(?:and|or)\b""", RegexOption.IGNORE_CASE)
    private val delimitedSplitRegex = Regex("""\s*(?:,|;|\band\b|\bor\b)\s*""", RegexOption.IGNORE_CASE)
    private val orderedCueRegex =
        Regex("""\b(?:step|steps|first|second|third|fourth|fifth|rank|ranking|priority|priorities|top|order|sequence)\b""", RegexOption.IGNORE_CASE)
    private val wordTokenRegex = Regex("""[A-Za-z0-9]+(?:['-][A-Za-z0-9]+)*""")
}
