package com.aaryaharkare.voicekeyboard.formatter

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DeterministicListFormatterUnsupportedLexicalNoiseTest {

    @Test
    fun evaluateUnsupportedLexicalNoiseDataset() {
        val formatter = DeterministicListFormatter()
        val cases = loadCases(UNSUPPORTED_LEXICAL_NOISE_RESOURCE)

        assertEquals("Unsupported lexical-noise dataset size changed unexpectedly", 3, cases.size)
        assertEquals("Unsupported lexical-noise ids must be unique", cases.size, cases.map { it.id }.toSet().size)

        val results = cases.map { benchmarkCase ->
            val analysis = formatter.analyze(benchmarkCase.text)
            val output = analysis.formattedText
            val predictedList = output.containsRenderedList()
            val predictedBulletCount = output.renderedListItemCount()
            UnsupportedNoiseResult(
                case = benchmarkCase,
                analysis = analysis,
                predictedList = predictedList,
                predictedBulletCount = predictedBulletCount,
                output = output,
            )
        }

        val summary =
            """
            Deterministic List Formatter Unsupported Lexical Noise
            cases=${results.size}
            
            results
            ${renderCases(results)}
            """.trimIndent()

        val reportFile = File("build/reports/formatter/list-noisy-asr-unsupported.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(summary + "\n")

        println(summary)
    }

    private fun loadCases(resource: String): List<UnsupportedNoiseCase> {
        val stream =
            javaClass.classLoader?.getResourceAsStream(resource)
                ?: error("Missing unsupported lexical-noise resource: $resource")
        val payload = stream.bufferedReader().use { it.readText() }
        val entries = JSONArray(payload)

        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                add(
                    UnsupportedNoiseCase(
                        id = item.getString("id"),
                        category = item.getString("category"),
                        shouldFormatAsList = item.getBoolean("shouldFormatAsList"),
                        expectedBulletCount = item.optInt("expectedBulletCount", -1).takeIf { it >= 0 },
                        text = item.getString("text"),
                    ),
                )
            }
        }
    }

    private fun renderCases(results: List<UnsupportedNoiseResult>): String {
        if (results.isEmpty()) return "None"
        return results.joinToString(separator = "\n\n") { result ->
            buildString {
                append(result.case.id)
                append(" [")
                append(result.case.category)
                append("] expected=")
                append(if (result.case.shouldFormatAsList) "LIST" else "PLAIN")
                append(" predicted=")
                append(if (result.predictedList) "LIST" else "PLAIN")
                append(" bullets=")
                append(result.predictedBulletCount)
                result.case.expectedBulletCount?.let {
                    append("/")
                    append(it)
                }
                append('\n')
                append("INPUT: ")
                append(result.case.text)
                append('\n')
                append("OUTPUT: ")
                append(result.output)
                append('\n')
                append("REASONS: ")
                append(reasons(result))
            }
        }
    }

    private fun reasons(result: UnsupportedNoiseResult): String {
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

    private data class UnsupportedNoiseCase(
        val id: String,
        val category: String,
        val shouldFormatAsList: Boolean,
        val expectedBulletCount: Int?,
        val text: String,
    )

    private data class UnsupportedNoiseResult(
        val case: UnsupportedNoiseCase,
        val analysis: DeterministicListFormatter.FormatAnalysis,
        val predictedList: Boolean,
        val predictedBulletCount: Int,
        val output: String,
    )

    companion object {
        private const val UNSUPPORTED_LEXICAL_NOISE_RESOURCE = "formatter/list_inference_noisy_asr_unsupported.json"
    }
}
