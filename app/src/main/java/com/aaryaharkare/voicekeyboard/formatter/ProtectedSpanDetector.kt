package com.aaryaharkare.voicekeyboard.formatter

internal data class ProtectedSpan(
    val label: String,
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
)

internal object ProtectedSpanDetector {
    private val emailRegex =
        Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val urlRegex =
        Regex("""\b(?:https?://|www\.)[^\s]+""", RegexOption.IGNORE_CASE)
    private val versionOrDecimalRegex =
        Regex("""\b\d+\.\d+(?:\.\d+)*\b""")
    private val timeRegex =
        Regex("""\b\d{1,2}:\d{2}(?:\s?[ap]\.?m\.?)?\b""", RegexOption.IGNORE_CASE)
    private val monthDateRegex =
        Regex(
            """\b(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)?\,?\s*(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2}(?:,\s*\d{4})?\b""",
            RegexOption.IGNORE_CASE,
        )
    private val isoDateRegex = Regex("""\b\d{4}[./-]\d{1,2}[./-]\d{1,2}\b""")
    private val streetAddressRegex =
        Regex(
            """\b\d+\s+[A-Z][A-Za-z0-9.'-]*(?:\s+[A-Z0-9][A-Za-z0-9.'-]*)*(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Way|Court|Ct|Suite|Ste|Floor|Fl|Unit|Apartment|Apt|Box)\b[^.]*""",
            RegexOption.IGNORE_CASE,
        )
    private val roleTitleRegex =
        Regex(
            """\b(?:Director|Vice President|Senior Manager|Principal Engineer|Product Manager|Engineering Manager|Staff Engineer|Senior Engineer|Head|Lead)\s*,\s*[A-Z][A-Za-z]+(?:\s+[A-Z][A-Za-z]+)*\b""",
        )

    fun detect(text: String): List<ProtectedSpan> {
        if (text.isBlank()) return emptyList()

        return buildList {
            addAll(findMatches(text, emailRegex, "email"))
            addAll(findMatches(text, urlRegex, "url"))
            addAll(findMatches(text, versionOrDecimalRegex, "numeric"))
            addAll(findMatches(text, timeRegex, "time"))
            addAll(findMatches(text, monthDateRegex, "date"))
            addAll(findMatches(text, isoDateRegex, "date"))
            addAll(findMatches(text, streetAddressRegex, "address"))
            addAll(findMatches(text, roleTitleRegex, "title"))
        }
            .distinctBy { Triple(it.startIndex, it.endIndex, it.text) }
            .sortedBy { it.startIndex }
    }

    fun preservedIn(
        before: String,
        after: String,
        spans: List<ProtectedSpan>,
    ): Boolean {
        return spans.all { span ->
            occurrenceCount(before, span.text) == occurrenceCount(after, span.text)
        }
    }

    fun toPromptLines(spans: List<ProtectedSpan>): String {
        if (spans.isEmpty()) return "- none"
        return spans.joinToString(separator = "\n") { span ->
            "- ${span.label}: ${span.text}"
        }
    }

    private fun findMatches(
        text: String,
        regex: Regex,
        label: String,
    ): List<ProtectedSpan> {
        return regex.findAll(text).map { match ->
            ProtectedSpan(
                label = label,
                text = match.value,
                startIndex = match.range.first,
                endIndex = match.range.last + 1,
            )
        }.toList()
    }

    private fun occurrenceCount(
        text: String,
        needle: String,
    ): Int {
        if (needle.isEmpty()) return 0
        return Regex(Regex.escape(needle)).findAll(text).count()
    }
}
