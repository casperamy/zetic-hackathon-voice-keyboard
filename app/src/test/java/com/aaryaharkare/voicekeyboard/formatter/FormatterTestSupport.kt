package com.aaryaharkare.voicekeyboard.formatter

internal fun String.containsRenderedList(): Boolean = lineSequence().any { it.isRenderedListLine() }

internal fun String.renderedListItemCount(): Int = lineSequence().count { it.isRenderedListLine() }

internal fun String.isRenderedListLine(): Boolean {
    val trimmed = trimStart()
    return trimmed.startsWith(DeterministicListFormatter.BULLET_PREFIX) || orderedListLineRegex.containsMatchIn(trimmed)
}

private val orderedListLineRegex = Regex("""^\d+\.\s+.+$""")
