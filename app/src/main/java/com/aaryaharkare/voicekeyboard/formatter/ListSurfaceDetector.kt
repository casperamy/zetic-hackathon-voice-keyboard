package com.aaryaharkare.voicekeyboard.formatter

internal data class ListSurfaceSignal(
    val shouldRun: Boolean,
    val reason: String,
)

internal object ListSurfaceDetector {
    private val explicitListLineRegex = Regex("""^\s*(?:[-*•]|\d+[.)])\s+.+$""")
    private val inlineNumberedRegex = Regex("""(?:^|[\s:;,.!?])\d+[.)]\s+\S""")
    private val leadInRegex =
        Regex(
            """\b(?:include|includes|included|contain|contains|contained|covers|covered|depends on|focused on|focuses on|highlighted|highlights|concerns|concern|needs|need|requires|require|steps|points|reasons|priorities|tasks|things)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val countIntroRegex =
        Regex(
            """\b(?:\d+|one|two|three|four|five|six|seven|eight|nine|ten)\s+(?:main\s+|next\s+|important\s+)?(?:things|items|steps|points|reasons|tasks|priorities)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val sentenceRunIntroRegex =
        Regex(
            """\b(?:here are|there are|the next steps|the important things|the main points|the priorities)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val sentenceSplitRegex = Regex("""(?<=[.!?])\s+|\n+""")

    fun detect(text: String): ListSurfaceSignal {
        if (text.isBlank()) {
            return ListSurfaceSignal(shouldRun = false, reason = "blank")
        }

        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.count { explicitListLineRegex.matches(it) } >= 2) {
            return ListSurfaceSignal(shouldRun = true, reason = "explicit_list_lines")
        }

        if (inlineNumberedRegex.findAll(text).count() >= 2) {
            return ListSurfaceSignal(shouldRun = true, reason = "inline_numbering")
        }

        val flattened = text.replace(Regex("""\s+"""), " ").trim()
        if (
            leadInRegex.containsMatchIn(flattened) &&
            (flattened.count { it == ',' } >= 2 ||
                flattened.contains(" and ", ignoreCase = true) ||
                flattened.contains(" or ", ignoreCase = true))
        ) {
            return ListSurfaceSignal(shouldRun = true, reason = "lead_in_sequence")
        }

        if (countIntroRegex.containsMatchIn(flattened) && flattened.count { it == ',' } >= 1) {
            return ListSurfaceSignal(shouldRun = true, reason = "count_intro")
        }

        val sentences = sentenceSplitRegex.split(flattened).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size >= 3 && sentenceRunIntroRegex.containsMatchIn(sentences.first())) {
            val shortFollowers =
                sentences.drop(1).takeWhile { sentence ->
                    val wordCount = sentence.split(Regex("""\s+""")).count { it.isNotBlank() }
                    wordCount in 1..12
                }
            if (shortFollowers.size >= 2) {
                return ListSurfaceSignal(shouldRun = true, reason = "sentence_run")
            }
        }

        return ListSurfaceSignal(shouldRun = false, reason = "no_surface_signal")
    }
}
