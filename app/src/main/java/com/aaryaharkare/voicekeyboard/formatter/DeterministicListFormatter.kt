package com.aaryaharkare.voicekeyboard.formatter

class DeterministicListFormatter : FormatterEngine {

    override fun format(text: String): String = analyze(text).formattedText

    internal fun analyze(text: String): FormatAnalysis {
        val normalizedInput = preNormalize(text)
        if (normalizedInput.isEmpty()) {
            return FormatAnalysis(
                source = text,
                normalizedInput = normalizedInput,
                formattedText = normalizedInput,
                paragraphAnalyses = emptyList(),
            )
        }

        val paragraphAnalyses = splitParagraphs(normalizedInput).map(::analyzeParagraph)
        val formattedText =
            paragraphAnalyses.joinToString(separator = "\n\n") { it.output }
                .trim()

        return FormatAnalysis(
            source = text,
            normalizedInput = normalizedInput,
            formattedText = formattedText,
            paragraphAnalyses = paragraphAnalyses,
        )
    }

    internal fun buildDebugTrace(analysis: FormatAnalysis): List<String> {
        val lines = mutableListOf<String>()

        analysis.paragraphAnalyses.forEachIndexed { paragraphIndex, paragraph ->
            paragraph.sentenceAnalyses.forEachIndexed { sentenceIndex, sentence ->
                lines += buildString {
                    append("paragraph=")
                    append(paragraphIndex + 1)
                    append(" sentence=")
                    append(sentenceIndex + 1)
                    append(" class=")
                    append(sentence.classification)
                    sentence.protectionReason?.let {
                        append(" protection=")
                        append(it)
                    }
                    append(" text=")
                    append(sentence.normalizedText)
                }
            }

            paragraph.candidateDecisions.forEachIndexed { decisionIndex, decision ->
                lines += buildString {
                    append("paragraph=")
                    append(paragraphIndex + 1)
                    append(" decision=")
                    append(decisionIndex + 1)
                    if (decision.startSentenceIndex != null && decision.endSentenceIndex != null) {
                        append(" sentences=")
                        append(decision.startSentenceIndex + 1)
                        append('-')
                        append(decision.endSentenceIndex + 1)
                    }
                    append(" class=")
                    append(decision.classification)
                    decision.type?.let {
                        append(" type=")
                        append(it)
                    }
                    append(" style=")
                    append(decision.renderStyle)
                    append(" numbering=")
                    append(decision.numberingConsistency)
                    append(" accepted=")
                    append(decision.accepted)
                    decision.rejectionReason?.let {
                        append(" reason=")
                        append(it)
                    }
                    decision.protectionReason?.let {
                        append(" protection=")
                        append(it)
                    }
                    if (decision.preamble.isNotBlank()) {
                        append(" preamble=")
                        append(decision.preamble)
                    }
                    if (decision.items.isNotEmpty()) {
                        append(" items=")
                        append(decision.items.joinToString(prefix = "[", postfix = "]"))
                    }
                    decision.spanStopReason?.let {
                        append(" stop=")
                        append(it)
                    }
                }
            }
        }

        return lines
    }

    private fun preNormalize(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trimEnd() }
            .joinToString(separator = "\n")
            .trim()
    }

    private fun splitParagraphs(text: String): List<String> {
        return text.split(Regex("""\n\s*\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun analyzeParagraph(paragraph: String): ParagraphAnalysis {
        detectExistingList(paragraph)?.let { return it }
        detectEmbeddedNumberedRun(paragraph)?.let { return it }

        val normalizedParagraph = flattenParagraph(paragraph)
        if (normalizedParagraph.isEmpty()) {
            return ParagraphAnalysis(
                source = paragraph,
                normalizedSource = normalizedParagraph,
                output = normalizedParagraph,
                sentenceAnalyses = emptyList(),
                candidateDecisions = emptyList(),
            )
        }

        val shieldedParagraph = shieldProtectedTokens(normalizedParagraph)
        val sentenceAnalyses =
            splitSentences(shieldedParagraph.text)
                .map { createSentenceAnalysis(it, shieldedParagraph.placeholders) }
                .filter { it.normalizedText.isNotEmpty() }

        if (sentenceAnalyses.isEmpty()) {
            return ParagraphAnalysis(
                source = paragraph,
                normalizedSource = normalizedParagraph,
                output = normalizedParagraph,
                sentenceAnalyses = emptyList(),
                candidateDecisions = emptyList(),
            )
        }

        val candidateDecisions = mutableListOf<CandidateDecision>()
        var sentenceIndex = 0
        while (sentenceIndex < sentenceAnalyses.size) {
            val currentSentence = sentenceAnalyses[sentenceIndex]
            if (currentSentence.classification == SentenceClassification.PROTECTED) {
                candidateDecisions += protectedDecision(currentSentence).withSentenceRange(sentenceIndex, sentenceIndex)
                sentenceIndex += 1
                continue
            }

            val sameSentenceDecision =
                detectSameSentenceCandidate(currentSentence)
                    .withSentenceRange(sentenceIndex, sentenceIndex)
            if (sameSentenceDecision.accepted) {
                candidateDecisions += sameSentenceDecision
                sentenceIndex += 1
                continue
            }

            if (sentenceIndex + 1 < sentenceAnalyses.size) {
                val crossSentenceDecision =
                    detectCrossSentenceCandidate(
                        introSentence = currentSentence,
                        listSentence = sentenceAnalyses[sentenceIndex + 1],
                    )
                if (crossSentenceDecision != null) {
                    candidateDecisions += crossSentenceDecision.withSentenceRange(sentenceIndex, sentenceIndex + 1)
                    sentenceIndex += 2
                    continue
                }
            }

            val sentenceRunDecision = detectSentenceRunCandidate(sentenceAnalyses, sentenceIndex)
            if (sentenceRunDecision != null) {
                candidateDecisions += sentenceRunDecision.decision.withSentenceRange(
                    sentenceIndex,
                    sentenceIndex + sentenceRunDecision.consumedSentenceCount - 1,
                )
                sentenceIndex += sentenceRunDecision.consumedSentenceCount
                continue
            }

            candidateDecisions += sameSentenceDecision
            sentenceIndex += 1
        }

        val output = buildParagraphOutput(candidateDecisions)
        return ParagraphAnalysis(
            source = paragraph,
            normalizedSource = normalizedParagraph,
            output = output,
            sentenceAnalyses = sentenceAnalyses,
            candidateDecisions = candidateDecisions,
        )
    }

    private fun flattenParagraph(paragraph: String): String {
        return paragraph.lines()
            .joinToString(separator = " ") { it.trim() }
            .replace(whitespaceRegex, " ")
            .trim()
    }

    private fun detectExistingList(paragraph: String): ParagraphAnalysis? {
        val lines = paragraph.lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }

        if (lines.size < 2) return null

        val parsedList = parseWholeParagraphExplicitList(lines) ?: return null
        val output = renderList("", parsedList.items, "", parsedList.renderStyle)
        val decision =
            CandidateDecision(
                type = if (parsedList.containsExplicitNumbers) CandidateType.EXISTING_NUMBERED else CandidateType.EXISTING_BULLETED,
                source = parsedList.source,
                normalizedSource = parsedList.source,
                classification = SentenceClassification.EXISTING_LIST,
                protectionReason = null,
                preamble = "",
                items = parsedList.items,
                accepted = true,
                rejectionReason = null,
                output = output,
                renderStyle = parsedList.renderStyle,
                numberingConsistency = parsedList.numberingConsistency,
            )

        return ParagraphAnalysis(
            source = paragraph,
            normalizedSource = paragraph,
            output = output,
            sentenceAnalyses = emptyList(),
            candidateDecisions = listOf(decision),
        )
    }

    private fun detectEmbeddedNumberedRun(paragraph: String): ParagraphAnalysis? {
        val inlineMatch = detectInlineEmbeddedNumberedRun(paragraph)
        if (inlineMatch != null) return buildEmbeddedNumberedParagraph(paragraph, inlineMatch)

        val lineMatch = detectLineEmbeddedNumberedRun(paragraph)
        if (lineMatch != null) return buildEmbeddedNumberedParagraph(paragraph, lineMatch)

        return null
    }

    private fun detectLineEmbeddedNumberedRun(paragraph: String): EmbeddedNumberedRunMatch? {
        val lines = paragraph.lines()
        if (lines.size < 2) return null

        var startLineIndex = -1
        while (startLineIndex + 1 < lines.size) {
            startLineIndex += 1
            if (!numberedListMarkerRegex.containsMatchIn(lines[startLineIndex].trimStart())) continue

            val items = mutableListOf<String>()
            val numbers = mutableListOf<Int>()
            val sourceLines = mutableListOf<String>()
            var currentItem: StringBuilder? = null
            var lineIndex = startLineIndex
            var consumedAny = false

            while (lineIndex < lines.size) {
                val rawLine = lines[lineIndex]
                val trimmedStart = rawLine.trimStart()
                val markerMatch = explicitListMarkerRegex.matchEntire(trimmedStart)
                when {
                    markerMatch != null -> {
                        currentItem?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(items::add)
                        numbers += markerMatch.groupValues[1].toInt()
                        currentItem = StringBuilder(markerMatch.groupValues[2].trim())
                        sourceLines += rawLine.trimEnd()
                        consumedAny = true
                        lineIndex += 1
                    }
                    currentItem != null && rawLine.isIndentedContinuationLine() -> {
                        currentItem.append(' ')
                        currentItem.append(rawLine.trim())
                        sourceLines += rawLine.trimEnd()
                        lineIndex += 1
                    }
                    else -> {
                        break
                    }
                }
            }

            currentItem?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(items::add)

            if (!consumedAny || items.size < 2 || numbers.size != items.size) continue

            val prefixText = lines.take(startLineIndex).joinToString(separator = "\n").trim()
            val suffixText = lines.drop(lineIndex).joinToString(separator = "\n").trim()
            if (prefixText.isEmpty() && suffixText.isEmpty()) return null

            val prefixSplit = splitPrefixAndPreamble(prefixText)
            return EmbeddedNumberedRunMatch(
                prefixText = prefixSplit.leadingText,
                preamble = prefixSplit.preamble,
                items = items.map { it.normalizeListItem() },
                suffixText = suffixText,
                source = sourceLines.joinToString(separator = "\n"),
                renderStyle = renderStyleForNumbers(numbers),
                numberingConsistency = numberingConsistency(numbers),
            )
        }

        return null
    }

    private fun detectInlineEmbeddedNumberedRun(paragraph: String): EmbeddedNumberedRunMatch? {
        val normalizedParagraph =
            paragraph.lines()
                .joinToString(separator = "\n") { it.trimEnd() }
                .trim()
        if (normalizedParagraph.isEmpty()) return null

        val shielded = shieldProtectedTokens(normalizedParagraph)
        val markers = findInlineNumberedMarkers(shielded.text)
        if (markers.size < 2) return null

        for (startMarkerIndex in markers.indices) {
            val items = mutableListOf<String>()
            val numbers = mutableListOf<Int>()
            var suffixText = ""
            var failed = false
            var markerIndex = startMarkerIndex

            while (markerIndex < markers.size) {
                val marker = markers[markerIndex]
                val nextMarker = markers.getOrNull(markerIndex + 1)
                val segmentEnd = nextMarker?.start ?: shielded.text.length
                val rawSegment = restorePlaceholders(shielded.text.substring(marker.contentStart, segmentEnd), shielded.placeholders)
                val extracted = extractInlineNumberedItem(rawSegment, isLast = nextMarker == null)
                if (extracted == null) {
                    failed = true
                    break
                }

                items += extracted.item.normalizeListItem()
                numbers += marker.number

                if (nextMarker == null) {
                    suffixText = extracted.trailingText
                } else if (extracted.trailingText.isNotEmpty()) {
                    failed = true
                    break
                }

                markerIndex += 1
                if (nextMarker == null) break
            }

            if (failed || items.size < 2 || numbers.size != items.size) continue

            val rawPrefix = restorePlaceholders(shielded.text.substring(0, markers[startMarkerIndex].start), shielded.placeholders).trim()
            if (rawPrefix.isEmpty() && suffixText.isEmpty()) return null

            val prefixSplit = splitPrefixAndPreamble(rawPrefix)
            return EmbeddedNumberedRunMatch(
                prefixText = prefixSplit.leadingText,
                preamble = prefixSplit.preamble,
                items = items,
                suffixText = suffixText.trim(),
                source = numbers.zip(items).joinToString(separator = " ") { (number, item) -> "$number. $item" },
                renderStyle = renderStyleForNumbers(numbers),
                numberingConsistency = numberingConsistency(numbers),
            )
        }

        return null
    }

    private fun buildEmbeddedNumberedParagraph(
        paragraph: String,
        match: EmbeddedNumberedRunMatch,
    ): ParagraphAnalysis {
        val candidateDecisions = mutableListOf<CandidateDecision>()
        val sentenceAnalyses = mutableListOf<SentenceAnalysis>()

        match.prefixText.takeIf { it.isNotBlank() }?.let { prefix ->
            val prefixAnalysis = analyzeParagraph(prefix)
            candidateDecisions += prefixAnalysis.candidateDecisions
            sentenceAnalyses += prefixAnalysis.sentenceAnalyses
        }

        candidateDecisions +=
            CandidateDecision(
                type = CandidateType.EMBEDDED_NUMBERED_RUN,
                source = match.source,
                normalizedSource = match.source,
                classification = SentenceClassification.INFERRED_NUMBERED_RUN,
                protectionReason = null,
                preamble = match.preamble,
                items = match.items,
                accepted = true,
                rejectionReason = null,
                output = renderList(match.preamble, match.items, "", match.renderStyle),
                renderStyle = match.renderStyle,
                numberingConsistency = match.numberingConsistency,
            )

        match.suffixText.takeIf { it.isNotBlank() }?.let { suffix ->
            val suffixAnalysis = analyzeParagraph(suffix)
            candidateDecisions += suffixAnalysis.candidateDecisions
            sentenceAnalyses += suffixAnalysis.sentenceAnalyses
        }

        val output = buildParagraphOutput(candidateDecisions)
        return ParagraphAnalysis(
            source = paragraph,
            normalizedSource = flattenParagraph(paragraph),
            output = output,
            sentenceAnalyses = sentenceAnalyses,
            candidateDecisions = candidateDecisions,
        )
    }

    private fun createSentenceAnalysis(
        shieldedSentence: String,
        placeholders: Map<String, String>,
    ): SentenceAnalysis {
        val restoredSource = restorePlaceholders(shieldedSentence, placeholders).trim()
        val normalizedText = normalizeInferenceSentence(restoredSource)
        val protectionReason =
            detectProtection(
                sentence = normalizedText,
                hadShieldedToken = protectedPlaceholderRegex.containsMatchIn(shieldedSentence),
            )

        return SentenceAnalysis(
            source = restoredSource,
            normalizedText = normalizedText,
            classification = if (protectionReason != null) SentenceClassification.PROTECTED else SentenceClassification.PLAIN,
            protectionReason = protectionReason,
        )
    }

    private fun detectProtection(
        sentence: String,
        hadShieldedToken: Boolean,
    ): ProtectionReason? {
        if (sentence.isBlank()) return null
        if (hadShieldedToken) return ProtectionReason.SHIELDED_TOKEN

        val lower = sentence.lowercase()
        if (identifierProtectionRegex.containsMatchIn(lower)) return ProtectionReason.IDENTIFIER_OR_CODE
        if (titleRoleProtectionRegex.containsMatchIn(lower)) return ProtectionReason.TITLE_OR_ROLE
        if (flightRoutingProtectionRegex.containsMatchIn(lower)) return ProtectionReason.FLIGHT_OR_ROUTING
        if (dateProtectionRegexes.any { it.containsMatchIn(lower) } || timeProtectionRegex.containsMatchIn(lower)) {
            return ProtectionReason.DATE_OR_TIME
        }
        if (locationProtectionRegex.containsMatchIn(lower)) return ProtectionReason.ADDRESS
        if (addressProtectionRegexes.any { it.containsMatchIn(lower) }) return ProtectionReason.ADDRESS

        return null
    }

    private fun detectCrossSentenceCandidate(
        introSentence: SentenceAnalysis,
        listSentence: SentenceAnalysis,
    ): CandidateDecision? {
        if (introSentence.classification == SentenceClassification.PROTECTED || listSentence.classification == SentenceClassification.PROTECTED) {
            return null
        }

        val intro = detectCrossSentenceIntro(introSentence.normalizedText) ?: return null
        val trimmedListSentence = listSentence.normalizedText.trim()
        val terminalPunctuation = terminalPunctuation(trimmedListSentence)
        val coreListSentence = trimmedListSentence.removeSuffix(terminalPunctuation).trim()
        val attempt =
            buildCandidateAttempt(
                source = "${introSentence.normalizedText} ${listSentence.normalizedText}".trim(),
                type = CandidateType.INFERRED_CROSS_SENTENCE,
                preamble = intro.preamble,
                rawItems = splitCandidateChunks(coreListSentence),
                terminalPunctuation = terminalPunctuation,
                classification = SentenceClassification.INFERRED_CROSS_SENTENCE,
                rejectSubjectLedSentence = true,
            )

        return attempt?.decision
    }

    private fun detectSentenceRunCandidate(
        sentences: List<SentenceAnalysis>,
        startIndex: Int,
    ): SpanCandidate? {
        val introSentence = sentences[startIndex]
        if (introSentence.classification == SentenceClassification.PROTECTED) return null

        val intro = detectSentenceRunIntro(introSentence.normalizedText) ?: return null
        val items = mutableListOf<String>()
        val sourceSentences = mutableListOf(introSentence.normalizedText)
        var stopReason: SpanStopReason? = null
        var currentIndex = startIndex + 1

        while (currentIndex < sentences.size && items.size < MAX_SENTENCE_RUN_ITEMS) {
            val itemAttempt = detectSentenceRunItem(sentences[currentIndex])
            if (itemAttempt.item == null) {
                stopReason = itemAttempt.stopReason
                break
            }

            items += itemAttempt.item
            sourceSentences += sentences[currentIndex].normalizedText
            currentIndex += 1
        }

        if (items.size < MIN_SENTENCE_RUN_ITEMS) return null

        return SpanCandidate(
            consumedSentenceCount = 1 + items.size,
            decision =
                CandidateDecision(
                    type = CandidateType.INFERRED_SENTENCE_RUN,
                    source = sourceSentences.joinToString(separator = " "),
                    normalizedSource = sourceSentences.joinToString(separator = " "),
                    classification = SentenceClassification.INFERRED_SENTENCE_RUN,
                    protectionReason = null,
                    preamble = intro.preamble,
                    items = items,
                    accepted = true,
                    rejectionReason = null,
                    output = renderList(intro.preamble, items, ""),
                    spanStopReason = stopReason,
                ),
        )
    }

    private fun detectSameSentenceCandidate(sentence: SentenceAnalysis): CandidateDecision {
        if (sentence.classification == SentenceClassification.PROTECTED) {
            return protectedDecision(sentence)
        }

        val trimmed = sentence.normalizedText.trim()
        val terminalPunctuation = terminalPunctuation(trimmed)
        val coreSentence = trimmed.removeSuffix(terminalPunctuation).trim()

        if (!looksLikeListSurface(coreSentence)) {
            return rejectedDecision(
                source = trimmed,
                normalizedSource = trimmed,
                classification = sentence.classification,
                protectionReason = sentence.protectionReason,
                reason = RejectionReason.NO_LIST_SEPARATORS,
            )
        }

        if (subordinateStarts.any { coreSentence.startsWith(it, ignoreCase = true) }) {
            return rejectedDecision(
                source = trimmed,
                normalizedSource = trimmed,
                classification = sentence.classification,
                protectionReason = sentence.protectionReason,
                reason = RejectionReason.SUBORDINATE_LEAD_IN,
            )
        }

        val explicitAttempt = detectExplicitCandidate(coreSentence, terminalPunctuation, trimmed)
        if (explicitAttempt?.decision != null) return explicitAttempt.decision

        val fallbackPreambleAttempt = detectFallbackPreambleCandidate(coreSentence, terminalPunctuation, trimmed)
        if (fallbackPreambleAttempt?.decision != null) return fallbackPreambleAttempt.decision

        val bareListAttempt = detectBareListCandidate(coreSentence, terminalPunctuation, trimmed)
        if (bareListAttempt?.decision != null) return bareListAttempt.decision

        val bestFailure =
            listOfNotNull(explicitAttempt, fallbackPreambleAttempt, bareListAttempt)
                .filter { it.rejectionReason != RejectionReason.NO_LIST_SEPARATORS }
                .maxByOrNull { rejectionPriority(it.rejectionReason) }

        return rejectedDecision(
            source = trimmed,
            normalizedSource = trimmed,
            classification = sentence.classification,
            protectionReason = sentence.protectionReason,
            type = CandidateType.INFERRED_PROSE,
            preamble = bestFailure?.preamble.orEmpty(),
            items = bestFailure?.items.orEmpty(),
            reason = bestFailure?.rejectionReason ?: RejectionReason.NO_LEAD_IN_MATCH,
        )
    }

    private fun detectExplicitCandidate(
        coreSentence: String,
        terminalPunctuation: String,
        source: String,
    ): CandidateAttempt? {
        val leadInMatch = findLeadIn(coreSentence) ?: return null

        var preamble = coreSentence.substring(0, leadInMatch.endIndex).trim().removeSuffix(":")
        var candidateList = coreSentence.substring(leadInMatch.endIndex).trim().removePrefix(":").trim()

        if (pronounStatePreambleRegex.containsMatchIn(preamble.lowercase())) {
            return CandidateAttempt(
                preamble = preamble,
                items = emptyList(),
                rejectionReason = RejectionReason.PROTECTED_PREAMBLE,
                decision = null,
            )
        }

        extractLeadingCountLabel(candidateList)?.let { countLabel ->
            preamble = "$preamble ${countLabel.label}".trim()
            candidateList = countLabel.remainder
        }

        return buildCandidateAttempt(
            source = source,
            type = CandidateType.INFERRED_PROSE,
            preamble = preamble,
            rawItems = splitCandidateChunks(candidateList),
            terminalPunctuation = terminalPunctuation,
            classification = SentenceClassification.INFERRED_SAME_SENTENCE,
            rejectSubjectLedSentence = false,
        )
    }

    private fun detectFallbackPreambleCandidate(
        coreSentence: String,
        terminalPunctuation: String,
        source: String,
    ): CandidateAttempt? {
        val chunks = splitCandidateChunks(coreSentence)
        if (chunks.size < 4) return null

        val preamble = chunks.first()
        if (!looksLikeFallbackPreamble(preamble)) return null

        return buildCandidateAttempt(
            source = source,
            type = CandidateType.INFERRED_PROSE,
            preamble = preamble.removeSuffix(":"),
            rawItems = chunks.drop(1),
            terminalPunctuation = terminalPunctuation,
            classification = SentenceClassification.INFERRED_SAME_SENTENCE,
            rejectSubjectLedSentence = false,
        )
    }

    private fun detectBareListCandidate(
        coreSentence: String,
        terminalPunctuation: String,
        source: String,
    ): CandidateAttempt? {
        return buildCandidateAttempt(
            source = source,
            type = CandidateType.INFERRED_PROSE,
            preamble = "",
            rawItems = splitCandidateChunks(coreSentence),
            terminalPunctuation = terminalPunctuation,
            classification = SentenceClassification.INFERRED_SAME_SENTENCE,
            rejectSubjectLedSentence = true,
        )
    }

    private fun buildCandidateAttempt(
        source: String,
        type: CandidateType,
        preamble: String,
        rawItems: List<String>,
        terminalPunctuation: String,
        classification: SentenceClassification,
        rejectSubjectLedSentence: Boolean,
    ): CandidateAttempt? {
        if (rawItems.isEmpty()) return null
        if (rawItems.size < 3) {
            return CandidateAttempt(
                preamble = preamble,
                items = rawItems,
                rejectionReason = RejectionReason.TOO_FEW_ITEMS,
                decision = null,
            )
        }

        val firstItemLower = rawItems.first().lowercase()
        if (rejectSubjectLedSentence &&
            (subjectPronounSentenceRegex.containsMatchIn(firstItemLower) || transitionSubjectSentenceRegex.containsMatchIn(firstItemLower))
        ) {
            return CandidateAttempt(
                preamble = preamble,
                items = rawItems,
                rejectionReason = RejectionReason.CLAUSE_LIKE_ITEMS,
                decision = null,
            )
        }

        val normalizedItems = rawItems.map { it.normalizeListItem() }
        if (normalizedItems.any { it.isBlank() || it.wordCount() > MAX_ITEM_WORDS || it.length > MAX_ITEM_LENGTH }) {
            return CandidateAttempt(
                preamble = preamble,
                items = normalizedItems,
                rejectionReason = RejectionReason.ITEM_LENGTH,
                decision = null,
            )
        }

        if (normalizedItems.any { it.startsWithDisallowedMarker() }) {
            return CandidateAttempt(
                preamble = preamble,
                items = normalizedItems,
                rejectionReason = RejectionReason.DISALLOWED_ITEM_PREFIX,
                decision = null,
            )
        }

        if (!normalizedItems.all { it.isPhraseLike() }) {
            return CandidateAttempt(
                preamble = preamble,
                items = normalizedItems,
                rejectionReason = RejectionReason.CLAUSE_LIKE_ITEMS,
                decision = null,
            )
        }

        val normalizedPreamble = preamble.trim().removeSuffix(":")
        val output = renderList(normalizedPreamble, normalizedItems, terminalPunctuation)
        return CandidateAttempt(
            preamble = normalizedPreamble,
            items = normalizedItems,
            rejectionReason = null,
            decision =
                CandidateDecision(
                    type = type,
                    source = source,
                    normalizedSource = source,
                    classification = classification,
                    protectionReason = null,
                    preamble = normalizedPreamble,
                    items = normalizedItems,
                    accepted = true,
                    rejectionReason = null,
                    output = output,
                ),
        )
    }

    private fun detectCrossSentenceIntro(sentence: String): CrossSentenceIntro? {
        val trimmed = sentence.trim()
        val coreSentence = trimmed.removeSuffix(terminalPunctuation(trimmed)).trim()
        if (coreSentence.contains(',')) return null
        if (coreSentence.wordCount() > MAX_CROSS_SENTENCE_INTRO_WORDS) return null
        if (!crossSentenceIntroPatterns.any { it.containsMatchIn(coreSentence) }) return null

        return CrossSentenceIntro(
            preamble = coreSentence.removeSuffix(":").trim(),
        )
    }

    private fun findLeadIn(sentence: String): LeadInMatch? {
        val firstSeparatorIndex = sentence.indexOf(',')
        val searchWindow =
            if (firstSeparatorIndex >= 0) {
                sentence.substring(0, firstSeparatorIndex)
            } else {
                sentence
            }

        var bestMatch: LeadInMatch? = null

        leadInPatterns.forEach { pattern ->
            pattern.regex.findAll(searchWindow).forEach { match ->
                val candidate = LeadInMatch(endIndex = match.range.last + 1, category = pattern.category)
                val currentBest = bestMatch
                if (currentBest == null || candidate.endIndex > currentBest.endIndex) {
                    bestMatch = candidate
                }
            }
        }

        return bestMatch
    }

    private fun extractLeadingCountLabel(candidateList: String): CountLabelMatch? {
        val match = leadingCountLabelRegex.find(candidateList) ?: return null
        val remainder = match.groupValues[2].trim()
        if (remainder.isEmpty()) return null

        return CountLabelMatch(
            label = match.groupValues[1].trim(),
            remainder = remainder,
        )
    }

    private fun looksLikeFallbackPreamble(chunk: String): Boolean {
        val lower = chunk.lowercase()
        if (chunk.wordCount() > MAX_FALLBACK_PREAMBLE_WORDS) return false
        if (fallbackPreambleKeywords.any { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) }) {
            return true
        }
        return listHeadWords.any { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) }
    }

    private fun looksLikeListSurface(sentence: String): Boolean {
        val chunks = splitCandidateChunks(sentence)
        return chunks.size >= 3
    }

    private fun splitCandidateChunks(text: String): List<String> {
        val normalized =
            text.replace(trailingConjunctionRegex, ", $2")
                .replace(whitespaceRegex, " ")
                .trim()
                .trim(',', ';', ':')

        if (normalized.isEmpty()) return emptyList()
        return normalized.split(itemSeparatorRegex)
            .map { it.trim().trim(',', ';', ':') }
            .filter { it.isNotEmpty() }
    }

    private fun normalizeInferenceSentence(sentence: String): String {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return trimmed

        val terminalPunctuation = terminalPunctuation(trimmed)
        var coreSentence = trimmed.removeSuffix(terminalPunctuation).trim()
        coreSentence = stripLeadingFillers(coreSentence)

        if (coreSentence.isEmpty()) return ""
        return buildString {
            append(coreSentence)
            append(terminalPunctuation)
        }
    }

    private fun detectSentenceRunIntro(sentence: String): SentenceRunIntro? {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return null

        val terminalPunctuation = terminalPunctuation(trimmed)
        val coreSentence = trimmed.removeSuffix(terminalPunctuation).trim().removeSuffix(":").trim()
        if (coreSentence.isEmpty() || coreSentence.contains(',')) return null

        val strippedCore = stripSentenceRunIntroTail(coreSentence)
        val candidate = strippedCore.ifEmpty { coreSentence }
        if (!sentenceRunIntroRegex.containsMatchIn(candidate.lowercase())) return null

        return SentenceRunIntro(
            preamble = candidate.trim(),
        )
    }

    private fun stripSentenceRunIntroTail(sentence: String): String {
        return sentenceRunIntroTailRegex.replace(sentence, "").trim().removeSuffix(",").trim()
    }

    private fun detectSentenceRunItem(sentence: SentenceAnalysis): SentenceRunItemAttempt {
        if (sentence.classification == SentenceClassification.PROTECTED) {
            return SentenceRunItemAttempt(stopReason = SpanStopReason.PROTECTED)
        }

        val trimmed = sentence.normalizedText.trim()
        if (trimmed.isEmpty()) return SentenceRunItemAttempt(stopReason = SpanStopReason.LONG_NARRATIVE)
        if (trimmed.endsWith("?")) return SentenceRunItemAttempt(stopReason = SpanStopReason.QUESTION)

        val strippedForDetection = stripLeadingCoordinator(trimmed)
        val normalizedItem =
            strippedForDetection.trim()
                .takeIf { it.isNotEmpty() }
                ?.let(::capitalizeSentenceStart)
                ?: return SentenceRunItemAttempt(stopReason = SpanStopReason.LONG_NARRATIVE)

        val lower = normalizedItem.lowercase()
        if (subordinateStarts.any { lower.startsWith(it) }) {
            return SentenceRunItemAttempt(stopReason = SpanStopReason.SUBORDINATE)
        }
        if (topicShiftStarts.any { lower.startsWith(it) }) {
            return SentenceRunItemAttempt(stopReason = SpanStopReason.TOPIC_SHIFT)
        }
        if (normalizedItem.wordCount() > MAX_SENTENCE_RUN_ITEM_WORDS || normalizedItem.length > MAX_SENTENCE_RUN_ITEM_LENGTH) {
            return SentenceRunItemAttempt(stopReason = SpanStopReason.LONG_NARRATIVE)
        }
        if (normalizedItem.contains(',')) {
            return SentenceRunItemAttempt(stopReason = SpanStopReason.LONG_NARRATIVE)
        }
        if (sentenceRunNarrativeStartRegex.containsMatchIn(lower)) {
            return SentenceRunItemAttempt(stopReason = SpanStopReason.NARRATIVE_FLOW)
        }
        if (!isSentenceRunItemLike(normalizedItem)) {
            return SentenceRunItemAttempt(stopReason = SpanStopReason.LONG_NARRATIVE)
        }

        return SentenceRunItemAttempt(item = normalizedItem)
    }

    private fun stripLeadingCoordinator(sentence: String): String {
        return sentence.replaceFirst(leadingCoordinatorRegex, "").trimStart()
    }

    private fun capitalizeSentenceStart(sentence: String): String {
        if (sentence.isEmpty()) return sentence
        return sentence.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    }

    private fun isSentenceRunItemLike(sentence: String): Boolean {
        val lower = sentence.lowercase()
        if (lower.isBlank()) return false
        if (subjectWithFiniteVerbRegex.containsMatchIn(lower) || bareSubjectWithFiniteVerbRegex.containsMatchIn(lower)) {
            return sentenceRunAllowedSubjectRegex.containsMatchIn(lower)
        }
        return true
    }

    private fun stripLeadingFillers(sentence: String): String {
        var result = sentence.trim()
        var removedAny = false

        while (true) {
            val updated = result.replaceFirst(leadingFillerRegex, "")
            if (updated == result) break
            result = updated.trimStart()
            removedAny = true
        }

        if (!removedAny || result.isEmpty()) return result
        return result.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    }

    private fun shieldProtectedTokens(text: String): ShieldedText {
        var shielded = text
        val placeholders = linkedMapOf<String, String>()
        var index = 0

        shieldingPatterns.forEach { pattern ->
            shielded =
                pattern.replace(shielded) { matchResult ->
                    val token = "__P${index++}__"
                    placeholders[token] = matchResult.value
                    token
                }
        }

        return ShieldedText(
            text = shielded,
            placeholders = placeholders,
        )
    }

    private fun restorePlaceholders(
        text: String,
        placeholders: Map<String, String>,
    ): String {
        var restored = text
        placeholders.forEach { (token, value) ->
            restored = restored.replace(token, value)
        }
        return restored
    }

    private fun CandidateDecision.withSentenceRange(
        startSentenceIndex: Int,
        endSentenceIndex: Int,
    ): CandidateDecision {
        return copy(
            startSentenceIndex = startSentenceIndex,
            endSentenceIndex = endSentenceIndex,
        )
    }

    private fun splitSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()

        text.forEachIndexed { index, character ->
            current.append(character)
            if (character == '.' || character == '!' || character == '?') {
                val next = text.getOrNull(index + 1)
                if (next == null || next.isWhitespace()) {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
                    current.clear()
                }
            }
        }

        current.toString().trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
        return sentences
    }

    private fun protectedDecision(sentence: SentenceAnalysis): CandidateDecision {
        return rejectedDecision(
            source = sentence.source,
            normalizedSource = sentence.normalizedText,
            classification = sentence.classification,
            protectionReason = sentence.protectionReason,
            reason = RejectionReason.PROTECTED_PREAMBLE,
            output = sentence.normalizedText,
        )
    }

    private fun buildParagraphOutput(candidateDecisions: List<CandidateDecision>): String {
        return buildString {
            candidateDecisions.forEachIndexed { index, decision ->
                if (index > 0) {
                    val previousWasBlock = candidateDecisions[index - 1].accepted
                    val currentIsBlock = decision.accepted
                    append(if (previousWasBlock || currentIsBlock) "\n\n" else " ")
                }
                append(decision.output)
            }
        }.trim()
    }

    private fun renderList(
        preamble: String,
        items: List<String>,
        terminalPunctuation: String,
        renderStyle: RenderStyle = RenderStyle.UNORDERED,
    ): String {
        return buildString {
            if (preamble.isNotBlank()) {
                append(preamble)
                append(':')
                append('\n')
            }
            items.forEachIndexed { index, item ->
                if (index > 0) append('\n')
                append(
                    when (renderStyle) {
                        RenderStyle.ORDERED -> "${index + 1}. "
                        RenderStyle.UNORDERED -> BULLET_PREFIX
                    },
                )
                append(item)
            }
            if (terminalPunctuation == "!" || terminalPunctuation == "?") {
                append(terminalPunctuation)
            }
        }
    }

    private fun parseWholeParagraphExplicitList(lines: List<String>): ParsedExplicitList? {
        val items = mutableListOf<String>()
        val numbers = mutableListOf<Int>()
        val sourceLines = mutableListOf<String>()
        var currentItem: StringBuilder? = null

        lines.forEach { line ->
            val trimmedStart = line.trimStart()
            val markerMatch = explicitOrBulletedListMarkerRegex.matchEntire(trimmedStart)
            if (markerMatch != null) {
                currentItem?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(items::add)
                markerMatch.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()?.let(numbers::add)
                currentItem = StringBuilder(markerMatch.groupValues[2].trim())
                sourceLines += line.trimEnd()
            } else if (currentItem != null && line.isIndentedContinuationLine()) {
                currentItem.append(' ')
                currentItem.append(line.trim())
                sourceLines += line.trimEnd()
            } else {
                return null
            }
        }

        currentItem?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(items::add)
        if (items.size < 2) return null

        val normalizedItems = items.map { it.normalizeListItem() }
        val consistency = if (numbers.isEmpty()) NumberingConsistency.NOT_NUMBERED else numberingConsistency(numbers)
        return ParsedExplicitList(
            source = sourceLines.joinToString(separator = "\n"),
            items = normalizedItems,
            renderStyle = if (consistency == NumberingConsistency.CONSISTENT) RenderStyle.ORDERED else RenderStyle.UNORDERED,
            numberingConsistency = consistency,
            containsExplicitNumbers = numbers.isNotEmpty(),
        )
    }

    private fun splitPrefixAndPreamble(prefixText: String): PrefixPreambleSplit {
        val trimmedPrefix = prefixText.trim()
        if (trimmedPrefix.isEmpty()) {
            return PrefixPreambleSplit(leadingText = "", preamble = "")
        }

        val lastBoundary = findLastSentenceBoundary(trimmedPrefix)
        if (lastBoundary >= 0) {
            val candidate = trimmedPrefix.substring(lastBoundary + 1).trim()
            val leading = trimmedPrefix.substring(0, lastBoundary + 1).trim()
            val normalizedPreamble = normalizeExplicitNumberedPreamble(candidate)
            if (normalizedPreamble != null) {
                return PrefixPreambleSplit(
                    leadingText = leading,
                    preamble = normalizedPreamble,
                )
            }
        }

        val fullPreamble = normalizeExplicitNumberedPreamble(trimmedPrefix)
        if (fullPreamble != null) {
            return PrefixPreambleSplit(leadingText = "", preamble = fullPreamble)
        }

        return PrefixPreambleSplit(leadingText = trimmedPrefix, preamble = "")
    }

    private fun findLastSentenceBoundary(text: String): Int {
        var boundaryIndex = -1
        text.forEachIndexed { index, character ->
            if (character == '.' || character == '!' || character == '?' || character == '\n') {
                boundaryIndex = index
            }
        }
        return boundaryIndex
    }

    private fun normalizeExplicitNumberedPreamble(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (explicitListMarkerRegex.containsMatchIn(trimmed)) return null

        val normalized =
            trimmed
                .removeSuffix(":")
                .removeSuffix(".")
                .removeSuffix("!")
                .removeSuffix("?")
                .trim()
        if (normalized.isEmpty()) return null
        if (!explicitNumberedPreambleRegex.containsMatchIn(normalized.lowercase())) return null
        return normalized
    }

    private fun renderStyleForNumbers(numbers: List<Int>): RenderStyle {
        return if (numberingConsistency(numbers) == NumberingConsistency.CONSISTENT) {
            RenderStyle.ORDERED
        } else {
            RenderStyle.UNORDERED
        }
    }

    private fun numberingConsistency(numbers: List<Int>): NumberingConsistency {
        if (numbers.isEmpty()) return NumberingConsistency.NOT_NUMBERED
        if (numbers.first() != 1) return NumberingConsistency.INCONSISTENT
        for (index in 1 until numbers.size) {
            if (numbers[index] != numbers[index - 1] + 1) {
                return NumberingConsistency.INCONSISTENT
            }
        }
        return NumberingConsistency.CONSISTENT
    }

    private fun findInlineNumberedMarkers(text: String): List<InlineNumberedMarker> {
        val markers = mutableListOf<InlineNumberedMarker>()
        var index = 0
        while (index < text.length) {
            if (!text[index].isDigit()) {
                index += 1
                continue
            }
            if (!hasNumberedMarkerBoundary(text, index)) {
                index += 1
                continue
            }

            var endDigits = index
            while (endDigits < text.length && text[endDigits].isDigit()) {
                endDigits += 1
            }
            if (endDigits >= text.length || (text[endDigits] != '.' && text[endDigits] != ')')) {
                index += 1
                continue
            }
            val contentStart = endDigits + 1
            if (contentStart >= text.length || !text[contentStart].isWhitespace()) {
                index += 1
                continue
            }
            val number = text.substring(index, endDigits).toIntOrNull()
            if (number == null) {
                index += 1
                continue
            }
            markers += InlineNumberedMarker(number = number, start = index, contentStart = contentStart + 1)
            index = contentStart + 1
        }
        return markers
    }

    private fun hasNumberedMarkerBoundary(
        text: String,
        index: Int,
    ): Boolean {
        var cursor = index - 1
        var sawLineBreak = false
        while (cursor >= 0 && text[cursor].isWhitespace()) {
            if (text[cursor] == '\n') sawLineBreak = true
            cursor -= 1
        }
        if (cursor < 0) return true
        if (sawLineBreak) return true
        return numberedRunBoundaryChars.contains(text[cursor])
    }

    private fun extractInlineNumberedItem(
        rawSegment: String,
        isLast: Boolean,
    ): InlineNumberedItem? {
        val trimmed = rawSegment.trim()
        if (trimmed.isEmpty()) return null

        if (!isLast) {
            if (containsNarrativeBeforeNextMarker(trimmed)) return null
            return InlineNumberedItem(item = trimmed.trim())
        }

        val firstLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
        val remainingAfterLine = trimmed.removePrefix(firstLine).trim()
        if (firstLine.isNotEmpty() && remainingAfterLine.isNotEmpty()) {
            return InlineNumberedItem(item = firstLine, trailingText = remainingAfterLine)
        }

        val sentences = splitSentences(trimmed)
        if (sentences.size >= 2) {
            val firstSentence = sentences.first().trim()
            val trailingText = trimmed.removePrefix(firstSentence).trim()
            if (firstSentence.wordCount() <= MAX_EMBEDDED_INLINE_ITEM_WORDS && trailingText.isNotEmpty()) {
                return InlineNumberedItem(item = firstSentence, trailingText = trailingText)
            }
        }

        return InlineNumberedItem(item = trimmed)
    }

    private fun containsNarrativeBeforeNextMarker(segment: String): Boolean {
        val sentences = splitSentences(segment)
        if (sentences.size <= 1) return false
        return true
    }

    private fun String.isIndentedContinuationLine(): Boolean {
        return isNotBlank() && firstOrNull()?.isWhitespace() == true
    }

    private fun rejectedDecision(
        source: String,
        normalizedSource: String,
        classification: SentenceClassification,
        protectionReason: ProtectionReason?,
        reason: RejectionReason,
        type: CandidateType? = null,
        preamble: String = "",
        items: List<String> = emptyList(),
        output: String = normalizedSource,
    ): CandidateDecision {
        return CandidateDecision(
            type = type,
            source = source,
            normalizedSource = normalizedSource,
            classification = classification,
            protectionReason = protectionReason,
            preamble = preamble,
            items = items,
            accepted = false,
            rejectionReason = reason,
            output = output,
        )
    }

    private fun terminalPunctuation(text: String): String {
        return when {
            text.endsWith(".") -> "."
            text.endsWith("!") -> "!"
            text.endsWith("?") -> "?"
            else -> ""
        }
    }

    private fun String.normalizeListItem(): String {
        return trim()
            .replace(whitespaceRegex, " ")
            .removePrefix(BULLET_PREFIX)
            .trim()
    }

    private fun String.wordCount(): Int {
        return trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .size
    }

    private fun String.startsWithDisallowedMarker(): Boolean {
        val lower = trim().lowercase()
        return disallowedItemStarts.any { lower.startsWith(it) }
    }

    private fun String.isPhraseLike(): Boolean {
        val normalized = trim()
        if (normalized.isEmpty()) return false
        val lower = normalized.lowercase()

        if (phraseProtectedWords.any { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) }) {
            return false
        }
        if (subordinateStarts.any { lower.startsWith(it) }) return false
        if (subjectWithFiniteVerbRegex.containsMatchIn(lower)) return false
        if (bareSubjectWithFiniteVerbRegex.containsMatchIn(lower)) return false

        return true
    }

    private fun rejectionPriority(reason: RejectionReason?): Int {
        return when (reason) {
            RejectionReason.PROTECTED_PREAMBLE -> 6
            RejectionReason.CLAUSE_LIKE_ITEMS -> 5
            RejectionReason.DISALLOWED_ITEM_PREFIX -> 4
            RejectionReason.ITEM_LENGTH -> 3
            RejectionReason.SUBORDINATE_LEAD_IN -> 2
            RejectionReason.TOO_FEW_ITEMS -> 1
            RejectionReason.NO_LEAD_IN_MATCH,
            RejectionReason.NO_LIST_SEPARATORS,
            null,
            -> 0
        }
    }

    internal data class FormatAnalysis(
        val source: String,
        val normalizedInput: String,
        val formattedText: String,
        val paragraphAnalyses: List<ParagraphAnalysis>,
    )

    internal data class ParagraphAnalysis(
        val source: String,
        val normalizedSource: String,
        val output: String,
        val sentenceAnalyses: List<SentenceAnalysis>,
        val candidateDecisions: List<CandidateDecision>,
    )

    internal data class SentenceAnalysis(
        val source: String,
        val normalizedText: String,
        val classification: SentenceClassification,
        val protectionReason: ProtectionReason?,
    )

    internal data class CandidateDecision(
        val type: CandidateType?,
        val source: String,
        val normalizedSource: String,
        val classification: SentenceClassification,
        val protectionReason: ProtectionReason?,
        val preamble: String,
        val items: List<String>,
        val accepted: Boolean,
        val rejectionReason: RejectionReason?,
        val output: String,
        val renderStyle: RenderStyle = RenderStyle.UNORDERED,
        val numberingConsistency: NumberingConsistency = NumberingConsistency.NOT_NUMBERED,
        val startSentenceIndex: Int? = null,
        val endSentenceIndex: Int? = null,
        val spanStopReason: SpanStopReason? = null,
    )

    private data class ShieldedText(
        val text: String,
        val placeholders: Map<String, String>,
    )

    private data class CandidateAttempt(
        val preamble: String,
        val items: List<String>,
        val rejectionReason: RejectionReason?,
        val decision: CandidateDecision?,
    )

    private data class SpanCandidate(
        val consumedSentenceCount: Int,
        val decision: CandidateDecision,
    )

    private data class ParsedExplicitList(
        val source: String,
        val items: List<String>,
        val renderStyle: RenderStyle,
        val numberingConsistency: NumberingConsistency,
        val containsExplicitNumbers: Boolean,
    )

    private data class PrefixPreambleSplit(
        val leadingText: String,
        val preamble: String,
    )

    private data class EmbeddedNumberedRunMatch(
        val prefixText: String,
        val preamble: String,
        val items: List<String>,
        val suffixText: String,
        val source: String,
        val renderStyle: RenderStyle,
        val numberingConsistency: NumberingConsistency,
    )

    private data class InlineNumberedMarker(
        val number: Int,
        val start: Int,
        val contentStart: Int,
    )

    private data class InlineNumberedItem(
        val item: String,
        val trailingText: String = "",
    )

    internal data class LeadInMatch(
        val endIndex: Int,
        val category: String,
    )

    internal data class CrossSentenceIntro(
        val preamble: String,
    )

    private data class SentenceRunIntro(
        val preamble: String,
    )

    internal data class CountLabelMatch(
        val label: String,
        val remainder: String,
    )

    private data class SentenceRunItemAttempt(
        val item: String? = null,
        val stopReason: SpanStopReason? = null,
    )

    private data class LeadInPattern(
        val regex: Regex,
        val category: String,
    )

    internal enum class CandidateType {
        EXISTING_NUMBERED,
        EXISTING_BULLETED,
        EMBEDDED_NUMBERED_RUN,
        INFERRED_PROSE,
        INFERRED_CROSS_SENTENCE,
        INFERRED_SENTENCE_RUN,
    }

    internal enum class SentenceClassification {
        PROTECTED,
        PLAIN,
        EXISTING_LIST,
        INFERRED_NUMBERED_RUN,
        INFERRED_SAME_SENTENCE,
        INFERRED_CROSS_SENTENCE,
        INFERRED_SENTENCE_RUN,
    }

    internal enum class RenderStyle {
        ORDERED,
        UNORDERED,
    }

    internal enum class NumberingConsistency {
        CONSISTENT,
        INCONSISTENT,
        NOT_NUMBERED,
    }

    internal enum class ProtectionReason {
        SHIELDED_TOKEN,
        ADDRESS,
        TITLE_OR_ROLE,
        IDENTIFIER_OR_CODE,
        DATE_OR_TIME,
        FLIGHT_OR_ROUTING,
    }

    internal enum class RejectionReason {
        NO_LIST_SEPARATORS,
        NO_LEAD_IN_MATCH,
        TOO_FEW_ITEMS,
        PROTECTED_PREAMBLE,
        ITEM_LENGTH,
        DISALLOWED_ITEM_PREFIX,
        CLAUSE_LIKE_ITEMS,
        SUBORDINATE_LEAD_IN,
    }

    internal enum class SpanStopReason {
        PROTECTED,
        TOPIC_SHIFT,
        QUESTION,
        SUBORDINATE,
        LONG_NARRATIVE,
        NARRATIVE_FLOW,
    }

    companion object {
        internal const val BULLET_PREFIX = "\u2022 "
        private const val MAX_ITEM_WORDS = 18
        private const val MAX_ITEM_LENGTH = 160
        private const val MAX_CROSS_SENTENCE_INTRO_WORDS = 18
        private const val MAX_FALLBACK_PREAMBLE_WORDS = 10
        private const val MIN_SENTENCE_RUN_ITEMS = 2
        private const val MAX_SENTENCE_RUN_ITEMS = 8
        private const val MAX_SENTENCE_RUN_ITEM_WORDS = 12
        private const val MAX_SENTENCE_RUN_ITEM_LENGTH = 90
        private const val MAX_EMBEDDED_INLINE_ITEM_WORDS = 12

        private val itemSeparatorRegex = Regex("""\s*,\s*""")
        private val whitespaceRegex = Regex("""\s+""")
        private val protectedPlaceholderRegex = Regex("""__P\d+__""")
        private val trailingConjunctionRegex = Regex("""\s*,?\s+(and|or)\s+([^,]+)$""", RegexOption.IGNORE_CASE)
        private val numberedListMarkerRegex = Regex("""^\d+[\.\)]\s+.*$""")
        private val listMarkerRegex = Regex("""^(?:\d+[\.\)]|[-*•])\s+(.*)$""")
        private val explicitListMarkerRegex = Regex("""^(\d+)[\.\)]\s+(.*)$""")
        private val explicitOrBulletedListMarkerRegex = Regex("""^(?:(\d+)[\.\)]|[-*•])\s+(.*)$""")
        private val leadingFillerRegex = Regex("""^(?:(?:okay|ok|so|right|well|but|um|uh|oh)\b(?:\s*,\s*|\s+))+""", RegexOption.IGNORE_CASE)
        private val leadingCoordinatorRegex = Regex("""^(?:and|or)\s+""", RegexOption.IGNORE_CASE)
        private val sentenceRunIntroTailRegex = Regex("""(?i)\s+(?:first of all|to be clear|basically)$""")
        private val numberedRunBoundaryChars = setOf('.', ':', '!', '?', ';')

        private val listHeadWords = listOf(
            "thing",
            "things",
            "item",
            "items",
            "step",
            "steps",
            "point",
            "points",
            "reason",
            "reasons",
            "priority",
            "priorities",
            "task",
            "tasks",
            "concern",
            "concerns",
            "constraint",
            "constraints",
            "risk",
            "risks",
            "goal",
            "goals",
            "part",
            "parts",
            "option",
            "options",
            "rule",
            "rules",
            "requirement",
            "requirements",
            "metric",
            "metrics",
            "category",
            "categories",
            "benefit",
            "benefits",
            "question",
            "questions",
            "value",
            "values",
            "channel",
            "channels",
            "habit",
            "habits",
            "theme",
            "themes",
            "gap",
            "gaps",
            "signal",
            "signals",
            "output",
            "outputs",
            "feature",
            "features",
            "aspect",
            "aspects",
            "deliverable",
            "deliverables",
            "milestone",
            "milestones",
            "issue",
            "issues",
            "problem",
            "problems",
            "factor",
            "factors",
            "component",
            "components",
            "topic",
            "topics",
            "area",
            "areas",
            "action",
            "actions",
            "dependency",
            "dependencies",
            "ingredient",
            "ingredients",
            "supply",
            "supplies",
            "grocery",
            "groceries",
            "list",
            "lists",
            "bullet",
            "bullets",
        )

        private val fallbackPreambleKeywords = listOf(
            "next steps",
            "action items",
            "shopping list",
            "priorities",
            "requirements",
            "questions",
            "benefits",
            "metrics",
            "reasons",
            "concerns",
            "risks",
            "constraints",
        )

        private val sentenceRunIntroKeywords = listOf(
            "next steps",
            "priorities",
            "reasons",
            "action items",
            "most important things",
            "key points",
            "benefits",
            "problems",
            "questions",
            "concerns",
            "requirements",
            "metrics",
            "items",
            "steps",
            "points",
            "things",
        )

        private val countWords = listOf(
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
        )

        private val listHeadPattern = listHeadWords.joinToString(separator = "|") { Regex.escape(it) }
        private val countWordPattern = countWords.joinToString(separator = "|") { Regex.escape(it) }
        private val sentenceRunIntroPattern = sentenceRunIntroKeywords.joinToString(separator = "|") { Regex.escape(it) }
        private val explicitNumberedPreambleRegex =
            Regex(
                """(?i)^(?:there\s+(?:are|were|will be)\b.*|here\s+(?:are|were)\b.*|.*\b(?:$listHeadPattern)\b.*(?:\b(?:are|is|were|was|include|includes|contain|contains|follow|follows|to this|below)\b.*)?)$""",
            )
        private val leadingCountLabelRegex =
            Regex("""^((?:(?:\d+|$countWordPattern)\s+(?:main\s+|next\s+|total\s+)?(?:$listHeadPattern)))\s*,\s*(.+)$""", RegexOption.IGNORE_CASE)

        private val leadInPatterns = listOf(
            LeadInPattern(Regex("""\bshould feel\b""", RegexOption.IGNORE_CASE), "quality"),
            LeadInPattern(Regex("""\bshould mention\b""", RegexOption.IGNORE_CASE), "directive"),
            LeadInPattern(Regex("""\bshould emphasize\b""", RegexOption.IGNORE_CASE), "directive"),
            LeadInPattern(Regex("""\basked about\b""", RegexOption.IGNORE_CASE), "action"),
            LeadInPattern(Regex("""\brests on\b""", RegexOption.IGNORE_CASE), "relationship"),
            LeadInPattern(Regex("""\bdepends on\b""", RegexOption.IGNORE_CASE), "relationship"),
            LeadInPattern(Regex("""\bdepends upon\b""", RegexOption.IGNORE_CASE), "relationship"),
            LeadInPattern(Regex("""\bconsist of\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bconsists of\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bcomposed of\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bmade up of\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bgrouped into\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bsplit into\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bdivided into\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bbroken into\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bbroken down into\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bboils down to\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bcomes down to\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bsuch as\b""", RegexOption.IGNORE_CASE), "example"),
            LeadInPattern(Regex("""\bincludes\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\binclude\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bincluding\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bcontains\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bcontain\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bcovers\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bcover\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bneeds\b""", RegexOption.IGNORE_CASE), "directive"),
            LeadInPattern(Regex("""\bneed\b""", RegexOption.IGNORE_CASE), "directive"),
            LeadInPattern(Regex("""\brequires\b""", RegexOption.IGNORE_CASE), "directive"),
            LeadInPattern(Regex("""\brequire\b""", RegexOption.IGNORE_CASE), "directive"),
            LeadInPattern(Regex("""\bconcerns\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bconcern\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\btouched\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\btouches\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bfocuses on\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bfocus on\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bfocused on\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bhighlights\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bhighlighted\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bmentions\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bmention\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bemphasize\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bemphasise\b""", RegexOption.IGNORE_CASE), "focus"),
            LeadInPattern(Regex("""\bspans\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bspan\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\btracks\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\btrack\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\boffers\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\boffer\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bhandles\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bhandle\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bbalances\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bbalance\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bbought\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bbuy\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bhas to handle\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bare\b""", RegexOption.IGNORE_CASE), "state"),
            LeadInPattern(Regex("""\bis\b""", RegexOption.IGNORE_CASE), "state"),
            LeadInPattern(Regex("""\bwas\b""", RegexOption.IGNORE_CASE), "state"),
            LeadInPattern(Regex("""\bwere\b""", RegexOption.IGNORE_CASE), "state"),
            LeadInPattern(Regex("""\bhas\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bhave\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\bhad\b""", RegexOption.IGNORE_CASE), "inventory"),
            LeadInPattern(Regex("""\blike\b""", RegexOption.IGNORE_CASE), "example"),
            LeadInPattern(Regex("""\bnamely\b""", RegexOption.IGNORE_CASE), "example"),
            LeadInPattern(Regex("""\bespecially\b""", RegexOption.IGNORE_CASE), "example"),
        )

        private val crossSentenceIntroPatterns = listOf(
            Regex("""(?i)^there\s+(?:are|were|will be)\s+(?:\w+\s+){0,6}(?:$listHeadPattern)\b.*$"""),
            Regex("""(?i)^here\s+(?:are|were)\s+(?:the\s+)?(?:\w+\s+){0,3}(?:$listHeadPattern)\b.*$"""),
            Regex("""(?i)^(?:(?:the|our|your|my)\s+)?(?:\w+\s+){0,3}(?:$listHeadPattern)\s+(?:are|were|remain|remains|follow|follows)\b.*$"""),
        )
        private val sentenceRunIntroRegex =
            Regex(
                """(?i)^(?:there\s+(?:are|were|will be)\b.*|here\s+(?:are|were)\b.*|.*\b(?:$sentenceRunIntroPattern)\b.*\b(?:are|is|were|was|remain|remains|follow|follows)\b.*)$""",
            )

        private val shieldingPatterns = listOf(
            Regex("""(?i)\b(?:https?://|www\.)\S+"""),
            Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""),
            Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""),
            Regex("""\b\d+\.\d+(?:\.\d+)+\b"""),
            Regex("""(?<![\w/])(?:\$?\d{1,3}(?:,\d{3})*|\$?\d+)\.\d+\b"""),
            Regex("""(?i)\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s,]*)?"""),
            Regex("""(?i)\b(?:a\.m\.|p\.m\.|e\.g\.|i\.e\.|p\.o\.)"""),
        )

        private val dateProtectionRegexes = listOf(
            Regex("""\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b"""),
            Regex("""(?i)\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""),
            Regex("""(?i)\b(?:january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\b"""),
        )

        private val timeProtectionRegex = Regex("""(?i)\b(?:\d{1,2}:\d{2}(?:\s*(?:a\.m\.|p\.m\.|am|pm))?|noon|midnight)\b""")
        private val titleRoleProtectionRegex = Regex("""(?i)\b(?:title|role)\s+is\b|\b(?:badge|sign|nameplate|placard)\s+(?:says|said|read|reads)\b""")
        private val identifierProtectionRegex = Regex("""(?i)\b(?:product code|sku|part number|serial number|model number|commit hash|branch name|tracking number)\b""")
        private val flightRoutingProtectionRegex = Regex("""(?i)\b(?:flight|gate|terminal|concourse|platform)\b""")
        private val locationProtectionRegex =
            Regex(
                """(?i)\b(?:office|apartment|clinic|hotel|house|home)\b.*,\s*(?:alabama|alaska|arizona|arkansas|california|colorado|connecticut|delaware|florida|georgia|hawaii|idaho|illinois|indiana|iowa|kansas|kentucky|louisiana|maine|maryland|massachusetts|michigan|minnesota|mississippi|missouri|montana|nebraska|nevada|new hampshire|new jersey|new mexico|new york|north carolina|north dakota|ohio|oklahoma|oregon|pennsylvania|rhode island|south carolina|south dakota|tennessee|texas|utah|vermont|virginia|washington|west virginia|wisconsin|wyoming)\b""",
            )
        private val addressProtectionRegexes = listOf(
            Regex("""(?i)\b(?:apartment|apt\.?|suite|unit|floor)\b"""),
            Regex("""(?i)\b(?:street|st\.?|avenue|ave\.?|road|rd\.?|boulevard|blvd\.?|lane|ln\.?|drive|dr\.?|court|ct\.?)\b"""),
            Regex("""(?i)\b\d+[a-z]?\s+[a-z0-9.\- ]+\b(?:street|st\.?|avenue|ave\.?|road|rd\.?|boulevard|blvd\.?|lane|ln\.?|drive|dr\.?|court|ct\.?)\b"""),
        )

        private val subordinateStarts = listOf(
            "if ",
            "when ",
            "after ",
            "before ",
            "while ",
            "because ",
            "although ",
            "though ",
            "unless ",
            "once ",
        )

        private val topicShiftStarts = listOf(
            "now ",
            "however ",
            "anyway ",
            "so now ",
            "but ",
            "then ",
            "later ",
            "afterward ",
            "how ",
            "why ",
            "what ",
        )

        private val disallowedItemStarts = listOf(
            "and ",
            "or ",
            "but ",
            "so ",
            "which ",
            "because ",
            "although ",
            "though ",
            "if ",
            "when ",
            "while ",
            "after ",
            "before ",
        )

        private val phraseProtectedWords = listOf(
            "because",
            "although",
            "though",
            "unless",
            "which",
        )

        private val subjectPronounSentenceRegex =
            Regex("""^(?:i|we|you|he|she|they|it|someone|somebody|nobody|everybody|everyone)\b""", RegexOption.IGNORE_CASE)

        private val transitionSubjectSentenceRegex =
            Regex("""^(?:then|next|later|afterward)\s+(?:i|we|you|he|she|they)\b""", RegexOption.IGNORE_CASE)

        private val pronounStatePreambleRegex =
            Regex("""^(?:it|this|that|he|she|they|we|i|you)\s+(?:is|are|was|were)\b""", RegexOption.IGNORE_CASE)

        private val sentenceRunAllowedSubjectRegex =
            Regex(
                """^(?:it|this|that)\b|^(?:we|you)\s+(?:need|want|should|must|have)\b""",
                RegexOption.IGNORE_CASE,
            )

        private val sentenceRunNarrativeStartRegex =
            Regex(
                """^(?:i|he|she|they|someone|somebody|nobody|everybody|everyone)\b|^(?:we|you)\s+(?!need\b|want\b|should\b|must\b|have\b)""",
                RegexOption.IGNORE_CASE,
            )

        private val subjectWithFiniteVerbRegex =
            Regex(
                """^(?:the|a|an|this|that|these|those|my|our|your|his|her|their|its|someone|somebody|nobody|everybody|everyone|i|we|you|he|she|they|users)\b.*\b(?:is|are|was|were|am|can|could|should|would|will|do|does|did|has|have|had|keeps?|kept|looks?|looked|lives?|lived|smells?|smelled|shakes?|shook|sleeps?|slept|trusted|wakes?|woke|closes?|closed|fails?|failed|arrives?|arrived|turns?|turned|rebooted|drained|cleared|compiled|stalled|waited|argued|left|talked|finished|signed|froze|filled|lost|complained|happened|dragged|wandered|felt|answered|stayed|slips?|stuttered|agreed|sounded|disagreed|missed)\b""",
                RegexOption.IGNORE_CASE,
            )

        private val bareSubjectWithFiniteVerbRegex =
            Regex(
                """^(?:[a-z][\w-]*)(?:\s+[a-z][\w-]*){0,2}\s+(?:is|are|was|were|feels?|felt|looks?|looked|seems?|seemed|stays?|stayed|slips?|slipped|signed|froze|filled|lost|wandered|dragged|agreed|stutters?|stuttered|arrived|missed)\b""",
                RegexOption.IGNORE_CASE,
            )
    }
}
