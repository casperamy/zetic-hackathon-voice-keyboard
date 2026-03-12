package com.aaryaharkare.voicekeyboard.formatter

internal data class PassTextResult(
    val text: String,
    val accepted: Boolean,
    val reason: String,
    val confidence: Double?,
)

internal data class ListPlanResolution(
    val acceptedPlans: List<ListPlan>,
    val reason: String,
    val confidence: Double?,
)

internal object FormatterPassValidator {
    private const val CLEANUP_MAX_EDIT_RATIO = 0.20
    private const val CLEANUP_MIN_CONFIDENCE = 0.60
    private const val CORRECTION_MIN_CONFIDENCE = 0.70
    private const val LIST_MIN_CONFIDENCE = 0.75

    private val bulletLineRegex = Regex("""^\s*(?:[-*•]|\d+[.)])\s+.+$""")
    private val orderedCueRegex =
        Regex("""\b(?:step|steps|first|second|third|fourth|fifth|rank|ranking|priority|priorities|top)\b""", RegexOption.IGNORE_CASE)
    private val strongIntroCueRegex =
        Regex("""\b(?:here are|there are|the next steps|the important things|the main points|the priorities|include|includes|contains|depends on|focused on|highlighted|reasons|tasks|things|points)\b""", RegexOption.IGNORE_CASE)
    private val clauseSubjectRegex =
        Regex("""^(?:i|we|you|he|she|they|it|the|this|that|these|those|my|our|their|his|her)\b""", RegexOption.IGNORE_CASE)
    private val clauseVerbRegex =
        Regex("""\b(?:am|is|are|was|were|be|been|being|have|has|had|do|does|did|went|said|looked|opened|closed|stalled|flaked|drained|cleared|smiled|waved|drove|called|answered|stayed|compiled|rebooted|missed|forgot|shake|shakes|smells|slept)\b""", RegexOption.IGNORE_CASE)
    private val verbLikeRegex = Regex("""\b[A-Za-z]+(?:ed|ing)\b""", RegexOption.IGNORE_CASE)

    fun validateCleanup(
        rawText: String,
        payload: CleanupPassPayload?,
        protectedSpans: List<ProtectedSpan>,
    ): PassTextResult {
        if (payload == null) {
            return PassTextResult(
                text = rawText,
                accepted = false,
                reason = "cleanup_parse_failed",
                confidence = null,
            )
        }

        val cleanedText = payload.cleanedText.trim()
        if (cleanedText.isEmpty()) {
            return reject(rawText, "cleanup_blank", payload.confidence)
        }
        if (payload.confidence < CLEANUP_MIN_CONFIDENCE) {
            return reject(rawText, "cleanup_low_confidence", payload.confidence)
        }
        if (cleanedText.count { it == '\n' } > rawText.count { it == '\n' }) {
            return reject(rawText, "cleanup_added_newlines", payload.confidence)
        }
        if (hasAdditionalListMarkers(rawText, cleanedText)) {
            return reject(rawText, "cleanup_added_list_markers", payload.confidence)
        }
        if (!ProtectedSpanDetector.preservedIn(rawText, cleanedText, protectedSpans)) {
            return reject(rawText, "cleanup_touched_protected_span", payload.confidence)
        }
        if (TextDiffUtils.editRatio(rawText, cleanedText) > CLEANUP_MAX_EDIT_RATIO) {
            return reject(rawText, "cleanup_edit_ratio_exceeded", payload.confidence)
        }

        return PassTextResult(
            text = cleanedText,
            accepted = true,
            reason = if (cleanedText == rawText) "cleanup_no_change" else "cleanup_applied",
            confidence = payload.confidence,
        )
    }

    fun validateCorrection(
        baselineText: String,
        payload: CorrectionPassPayload?,
        protectedSpans: List<ProtectedSpan>,
    ): PassTextResult {
        if (payload == null) {
            return PassTextResult(
                text = baselineText,
                accepted = false,
                reason = "correction_parse_failed",
                confidence = null,
            )
        }

        val correctedText = payload.correctedText.trim()
        if (correctedText.isEmpty()) {
            return reject(baselineText, "correction_blank", payload.confidence)
        }
        if (payload.confidence < CORRECTION_MIN_CONFIDENCE) {
            return reject(baselineText, "correction_low_confidence", payload.confidence)
        }
        if (correctedText.count { it == '\n' } > baselineText.count { it == '\n' }) {
            return reject(baselineText, "correction_added_newlines", payload.confidence)
        }
        if (hasAdditionalListMarkers(baselineText, correctedText)) {
            return reject(baselineText, "correction_added_list_markers", payload.confidence)
        }
        if (!ProtectedSpanDetector.preservedIn(baselineText, correctedText, protectedSpans)) {
            return reject(baselineText, "correction_touched_protected_span", payload.confidence)
        }

        val changedTokenCount = TextDiffUtils.changedTokenCount(baselineText, correctedText)
        if (changedTokenCount > TextDiffUtils.maxCorrectionChanges(baselineText)) {
            return reject(baselineText, "correction_change_limit_exceeded", payload.confidence)
        }
        if (correctedText != baselineText && payload.replacements.isEmpty()) {
            return reject(baselineText, "correction_missing_replacements", payload.confidence)
        }

        return PassTextResult(
            text = correctedText,
            accepted = true,
            reason = if (correctedText == baselineText) "correction_no_change" else "correction_applied",
            confidence = payload.confidence,
        )
    }

    fun resolveListPlans(
        text: String,
        payload: ListPlanPayload?,
        protectedSpans: List<ProtectedSpan>,
    ): ListPlanResolution {
        if (payload == null) {
            return ListPlanResolution(
                acceptedPlans = emptyList(),
                reason = "list_parse_failed",
                confidence = null,
            )
        }

        if (payload.plans.isEmpty()) {
            return ListPlanResolution(
                acceptedPlans = emptyList(),
                reason = "list_no_plans",
                confidence = payload.confidence,
            )
        }

        val accepted = mutableListOf<ListPlan>()
        val occupiedRanges = mutableListOf<IntRange>()

        payload.plans.forEachIndexed { index, rawPlan ->
            val spanRange = findUniqueRange(text, rawPlan.sourceSpanText)
                ?: return@forEachIndexed
            if (occupiedRanges.any { it.first < spanRange.last + 1 && spanRange.first < it.last + 1 }) {
                return@forEachIndexed
            }

            val sourceText = text.substring(spanRange)
            val sanitizedItems =
                rawPlan.items
                    .map(::sanitizeListItem)
                    .filter { it.isNotEmpty() }
            if (sanitizedItems.size < 2) return@forEachIndexed

            val containsProtectedContent =
                protectedSpans.any { span ->
                    span.startIndex >= spanRange.first && span.endIndex <= spanRange.last + 1
                } || ProtectedSpanDetector.detect(sourceText).isNotEmpty()
            if (containsProtectedContent && rawPlan.evidenceType == EvidenceType.STANDALONE_ITEM_SEQUENCE) {
                return@forEachIndexed
            }

            val strongCue =
                rawPlan.evidenceType != EvidenceType.STANDALONE_ITEM_SEQUENCE ||
                    strongIntroCueRegex.containsMatchIn(rawPlan.introText) ||
                    strongIntroCueRegex.containsMatchIn(sourceText)
            val explicit =
                rawPlan.evidenceType == EvidenceType.EXPLICIT_MARKERS ||
                    rawPlan.evidenceType == EvidenceType.INLINE_NUMBERED_SEQUENCE ||
                    sourceText.lines().count { bulletLineRegex.matches(it) } >= 2

            if (!explicit && rawPlan.confidence < LIST_MIN_CONFIDENCE) return@forEachIndexed
            if (!explicit && !strongCue && sanitizedItems.size < 3) return@forEachIndexed
            if (!strongCue && sanitizedItems.all(::looksLikeClause)) return@forEachIndexed

            val effectiveType =
                if (rawPlan.listType == ListType.ORDERED && !hasOrderedCue(rawPlan, sourceText)) {
                    ListType.UNORDERED
                } else {
                    rawPlan.listType
                }

            occupiedRanges += spanRange
            accepted +=
                rawPlan.copy(
                    introText = rawPlan.introText.trim().removeSuffix(":").trim(),
                    items = sanitizedItems,
                    listType = effectiveType,
                    startIndex = spanRange.first,
                    endIndex = spanRange.last + 1,
                    orderedReason = rawPlan.orderedReason?.trim()?.ifEmpty { null },
                )
        }

        return ListPlanResolution(
            acceptedPlans = accepted.sortedBy { it.startIndex ?: Int.MAX_VALUE },
            reason =
                when {
                    accepted.isEmpty() -> "list_no_accepted_plans"
                    accepted.size < payload.plans.size -> "list_partial_accept"
                    else -> "list_applied"
                },
            confidence = payload.confidence,
        )
    }

    private fun reject(
        baselineText: String,
        reason: String,
        confidence: Double?,
    ): PassTextResult {
        return PassTextResult(
            text = baselineText,
            accepted = false,
            reason = reason,
            confidence = confidence,
        )
    }

    private fun hasAdditionalListMarkers(
        before: String,
        after: String,
    ): Boolean {
        val beforeCount = before.lines().count { bulletLineRegex.matches(it) }
        val afterCount = after.lines().count { bulletLineRegex.matches(it) }
        return afterCount > beforeCount
    }

    private fun sanitizeListItem(item: String): String {
        return item
            .trim()
            .removePrefix("•")
            .replace(Regex("""^\d+[.)]\s+"""), "")
            .replace(Regex("""^\s*[-*]\s+"""), "")
            .trim()
    }

    private fun looksLikeClause(item: String): Boolean {
        val trimmed = item.trim().removeSuffix(".")
        val wordCount = trimmed.split(Regex("""\s+""")).count { it.isNotBlank() }
        return wordCount >= 4 &&
            (
                clauseVerbRegex.containsMatchIn(trimmed) ||
                    verbLikeRegex.containsMatchIn(trimmed) ||
                    clauseSubjectRegex.containsMatchIn(trimmed)
            )
    }

    private fun hasOrderedCue(
        plan: ListPlan,
        sourceText: String,
    ): Boolean {
        return plan.evidenceType == EvidenceType.EXPLICIT_MARKERS ||
            plan.evidenceType == EvidenceType.INLINE_NUMBERED_SEQUENCE ||
            orderedCueRegex.containsMatchIn(plan.orderedReason.orEmpty()) ||
            orderedCueRegex.containsMatchIn(plan.introText) ||
            orderedCueRegex.containsMatchIn(sourceText) ||
            Regex("""(?:^|[\s:;,.!?])\d+[.)]\s+\S""").containsMatchIn(sourceText)
    }

    private fun findUniqueRange(
        text: String,
        sourceSpanText: String,
    ): IntRange? {
        if (sourceSpanText.isBlank()) return null

        var startIndex = text.indexOf(sourceSpanText)
        if (startIndex == -1) return null

        var count = 0
        var uniqueRange: IntRange? = null
        while (startIndex != -1) {
            count += 1
            uniqueRange = startIndex until (startIndex + sourceSpanText.length)
            if (count > 1) {
                return null
            }
            startIndex = text.indexOf(sourceSpanText, startIndex + sourceSpanText.length)
        }

        return uniqueRange
    }
}
