package com.aaryaharkare.voicekeyboard.formatter

import android.view.inputmethod.EditorInfo

object FormatterDecisionPolicy {
    const val DEFAULT_CONFIDENCE_THRESHOLD = 0.65
    const val LIST_CONFIDENCE_THRESHOLD = 0.30

    data class CommitDecision(
        val finalText: String,
        val changesApplied: Boolean,
        val reason: String,
    )

    fun shouldAttemptFormatting(
        formatterEnabled: Boolean,
        inputType: Int,
    ): Boolean {
        if (!formatterEnabled) return false
        if (isPasswordField(inputType)) return false

        val inputClass = inputType and EditorInfo.TYPE_MASK_CLASS
        if (inputClass != EditorInfo.TYPE_CLASS_TEXT) return false

        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        if (variation in STRUCTURED_TEXT_VARIATIONS) return false

        return true
    }

    fun chooseFinalText(
        rawText: String,
        formattedText: String,
        confidence: Double,
        hasList: Boolean = false,
        needsFormatting: Boolean = false,
        threshold: Double = DEFAULT_CONFIDENCE_THRESHOLD,
    ): CommitDecision {
        val safeRaw = rawText.trim()
        val safeFormatted = formattedText.trim()

        if (safeRaw.isEmpty()) {
            return CommitDecision(
                finalText = "",
                changesApplied = false,
                reason = "empty_raw_text",
            )
        }

        if (safeFormatted.isEmpty()) {
            return CommitDecision(
                finalText = safeRaw,
                changesApplied = false,
                reason = "missing_formatted_text",
            )
        }

        val effectiveThreshold =
            if ((hasList || looksLikeBulletList(safeFormatted)) && looksLikeBulletList(safeFormatted)) {
                LIST_CONFIDENCE_THRESHOLD
            } else {
                threshold
            }

        if (confidence < effectiveThreshold) {
            return CommitDecision(
                finalText = safeRaw,
                changesApplied = false,
                reason = "low_confidence_fallback",
            )
        }

        return CommitDecision(
            finalText = safeFormatted,
            changesApplied = safeFormatted != safeRaw,
            reason = "formatter_applied",
        )
    }

    private fun isPasswordField(inputType: Int): Boolean {
        val maskClass = inputType and EditorInfo.TYPE_MASK_CLASS
        val maskVariation = inputType and EditorInfo.TYPE_MASK_VARIATION
        return when (maskClass) {
            EditorInfo.TYPE_CLASS_TEXT ->
                maskVariation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                    maskVariation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    maskVariation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            EditorInfo.TYPE_CLASS_NUMBER -> maskVariation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun looksLikeBulletList(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return false

        val bulletLike =
            lines.count {
                it.startsWith("- ") ||
                    it.startsWith("* ") ||
                    it.matches(Regex("""\d+\.\s+.+"""))
            }
        return bulletLike >= 2
    }

    private val STRUCTURED_TEXT_VARIATIONS =
        setOf(
            EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            EditorInfo.TYPE_TEXT_VARIATION_URI,
            EditorInfo.TYPE_TEXT_VARIATION_FILTER,
            EditorInfo.TYPE_TEXT_VARIATION_PHONETIC,
            EditorInfo.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
        )
}
