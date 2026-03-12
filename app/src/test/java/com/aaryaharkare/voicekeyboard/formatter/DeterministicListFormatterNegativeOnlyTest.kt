package com.aaryaharkare.voicekeyboard.formatter

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class DeterministicListFormatterNegativeOnlyTest {

    @Test
    fun negativeOnlyDatasetPreservesPlainText() {
        val formatter = DeterministicListFormatter()
        val cases = loadCases(NEGATIVE_ONLY_DATASET_RESOURCE)

        assertEquals("Negative-only dataset size changed unexpectedly", 100, cases.size)
        assertEquals("Negative-only ids must be unique", cases.size, cases.map { it.id }.toSet().size)
        assertTrue("Negative-only dataset must stay fully negative", cases.none { it.shouldFormatAsList })

        val results = cases.map { negativeCase ->
            val expectedOutput = expectedPlainOutput(negativeCase.text, negativeCase.category)
            val analysis = formatter.analyze(negativeCase.text)
            val output = analysis.formattedText
            val predictedList = output.containsRenderedList()
            NegativeOnlyResult(
                case = negativeCase,
                expectedOutput = expectedOutput,
                analysis = analysis,
                predictedList = predictedList,
                output = output,
            )
        }

        val falsePositives = results.filter { it.predictedList }
        val exactMatches = results.filter { !it.predictedList && it.output == it.expectedOutput }
        val unexpectedTextChanges = results.filter { !it.predictedList && it.output != it.expectedOutput }

        val categoryBreakdown =
            results.groupBy { it.case.category }
                .mapValues { (_, categoryResults) ->
                    percent(
                        numerator = categoryResults.count { !it.predictedList && it.output == it.expectedOutput },
                        denominator = categoryResults.size,
                    )
                }
                .toSortedMap()

        val falsePositivesByCategory =
            falsePositives.groupingBy { it.case.category }
                .eachCount()
                .toSortedMap()

        val summary =
            """
            Deterministic List Formatter Negative Only
            cases=${results.size}
            false_positive_rate=${percent(falsePositives.size, results.size)}%
            exact_text_preservation=${percent(exactMatches.size, results.size)}%
            
            category_breakdown
            ${renderCountMap(categoryBreakdown)}
            
            false_positives_by_category
            ${renderCountMap(falsePositivesByCategory)}
            
            unexpected_text_changes
            ${renderCases(unexpectedTextChanges)}
            
            false_positives
            ${renderCases(falsePositives)}
            """.trimIndent()

        val reportFile = File("build/reports/formatter/list-negative-only.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(summary + "\n")

        println(summary)

        val protectedFalsePositives = falsePositives.count { it.case.category == "negative_protected" }
        val structuredPlainChanges = unexpectedTextChanges.filter { it.case.category == "negative_structured_plain" }

        assertTrue("Protected negative cases formatted as lists", protectedFalsePositives == 0)
        assertTrue("Structured negative cases changed unexpectedly", structuredPlainChanges.isEmpty())
        assertTrue("Negative-only false positive rate too high", percent(falsePositives.size, results.size) <= MAX_FALSE_POSITIVE_RATE)
        assertTrue("Negative-only exact preservation too low", percent(exactMatches.size, results.size) >= MIN_EXACT_PRESERVATION)
        assertTrue("Plain-text output changed beyond filler removal", unexpectedTextChanges.isEmpty())
    }

    private fun loadCases(resource: String): List<NegativeOnlyCase> {
        val stream =
            javaClass.classLoader?.getResourceAsStream(resource)
                ?: error("Missing negative-only resource: $resource")
        val payload = stream.bufferedReader().use { it.readText() }
        val entries = JSONArray(payload)

        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                add(
                    NegativeOnlyCase(
                        id = item.getString("id"),
                        category = item.getString("category"),
                        shouldFormatAsList = item.getBoolean("shouldFormatAsList"),
                        text = item.getString("text"),
                    ),
                )
            }
        }
    }

    private fun expectedPlainOutput(
        text: String,
        category: String,
    ): String {
        if (category != "negative_noisy_plain") return text

        var normalized = text.trim()
        while (true) {
            val updated = normalized.replaceFirst(leadingFillerRegex, "").trimStart()
            if (updated == normalized) break
            normalized = updated
        }
        if (normalized.isEmpty()) return normalized
        return normalized.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    }

    private fun renderCases(results: List<NegativeOnlyResult>): String {
        if (results.isEmpty()) return "None"
        return results.joinToString(separator = "\n\n") { result ->
            buildString {
                append(result.case.id)
                append(" [")
                append(result.case.category)
                append("] predicted=")
                append(if (result.predictedList) "LIST" else "PLAIN")
                append('\n')
                append("INPUT: ")
                append(result.case.text)
                append('\n')
                append("EXPECTED: ")
                append(result.expectedOutput)
                append('\n')
                append("OUTPUT: ")
                append(result.output)
                append('\n')
                append("REASONS: ")
                append(reasons(result))
            }
        }
    }

    private fun reasons(result: NegativeOnlyResult): String {
        val decisions = result.analysis.paragraphAnalyses.flatMap { it.candidateDecisions }
        if (decisions.isEmpty()) return "no-candidates"
        return decisions.joinToString(separator = "; ") { decision ->
            buildString {
                append(decision.type ?: "none")
                append(':')
                append(if (decision.accepted) "accepted" else decision.rejectionReason ?: "rejected")
                if (decision.items.isNotEmpty()) {
                    append(" items=")
                    append(decision.items.joinToString(prefix = "[", postfix = "]"))
                }
            }
        }
    }

    private fun renderCountMap(values: Map<String, Int>): String {
        if (values.isEmpty()) return "  none"
        return values.entries.joinToString(separator = "\n") { (key, value) ->
            "  $key: $value"
        }
    }

    private fun percent(numerator: Int, denominator: Int): Int {
        if (denominator == 0) return 0
        return ((numerator.toDouble() / denominator.toDouble()) * 100.0).roundToInt()
    }

    private data class NegativeOnlyCase(
        val id: String,
        val category: String,
        val shouldFormatAsList: Boolean,
        val text: String,
    )

    private data class NegativeOnlyResult(
        val case: NegativeOnlyCase,
        val expectedOutput: String,
        val analysis: DeterministicListFormatter.FormatAnalysis,
        val predictedList: Boolean,
        val output: String,
    )

    companion object {
        private const val NEGATIVE_ONLY_DATASET_RESOURCE = "formatter/list_inference_negative_only.json"
        private const val MAX_FALSE_POSITIVE_RATE = 1
        private const val MIN_EXACT_PRESERVATION = 95
        private val leadingFillerRegex = Regex("""^(?:(?:okay|ok|so|right|well|but|um|uh|oh)\b(?:\s*,\s*|\s+))+""", RegexOption.IGNORE_CASE)
    }
}
