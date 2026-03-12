package com.aaryaharkare.voicekeyboard.formatter

import kotlin.math.ceil
import kotlin.math.min

internal object TextDiffUtils {
    private val tokenRegex = Regex("""[A-Za-z0-9]+(?:['.-][A-Za-z0-9]+)*|[^\s]""")

    fun editRatio(
        before: String,
        after: String,
    ): Double {
        val beforeTokens = tokenize(before)
        val afterTokens = tokenize(after)
        val denominator = maxOf(beforeTokens.size, afterTokens.size, 1)
        return tokenEditDistance(beforeTokens, afterTokens).toDouble() / denominator.toDouble()
    }

    fun changedTokenCount(
        before: String,
        after: String,
    ): Int {
        return tokenEditDistance(tokenize(before), tokenize(after))
    }

    fun maxCorrectionChanges(text: String): Int {
        val tokenCount = tokenize(text).size
        return maxOf(2, ceil(tokenCount * 0.15).toInt())
    }

    internal fun tokenize(text: String): List<String> {
        return tokenRegex.findAll(text)
            .map { it.value }
            .toList()
    }

    private fun tokenEditDistance(
        before: List<String>,
        after: List<String>,
    ): Int {
        if (before.isEmpty()) return after.size
        if (after.isEmpty()) return before.size

        var previous = IntArray(after.size + 1) { it }
        var current = IntArray(after.size + 1)

        for (beforeIndex in before.indices) {
            current[0] = beforeIndex + 1
            for (afterIndex in after.indices) {
                val substitutionCost = if (before[beforeIndex] == after[afterIndex]) 0 else 1
                current[afterIndex + 1] =
                    min(
                        min(previous[afterIndex + 1] + 1, current[afterIndex] + 1),
                        previous[afterIndex] + substitutionCost,
                    )
            }

            val swap = previous
            previous = current
            current = swap
        }

        return previous[after.size]
    }
}
