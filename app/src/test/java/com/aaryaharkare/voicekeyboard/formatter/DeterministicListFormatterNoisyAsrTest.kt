package com.aaryaharkare.voicekeyboard.formatter

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class DeterministicListFormatterNoisyAsrTest {

    @Test
    fun evaluateNoisyAsrDataset() {
        val formatter = DeterministicListFormatter()
        val cases = loadCases(NOISY_ASR_DATASET_RESOURCE)

        assertEquals("Supported noisy ASR dataset size changed unexpectedly", 27, cases.size)
        assertEquals("Noisy ASR ids must be unique", cases.size, cases.map { it.id }.toSet().size)
        assertTrue("Noisy ASR dataset must contain positive and negative cases", cases.any { it.shouldFormatAsList } && cases.any { !it.shouldFormatAsList })

        val results = cases.map { benchmarkCase ->
            val analysis = formatter.analyze(benchmarkCase.text)
            val output = analysis.formattedText
            val predictedList = output.containsRenderedList()
            val predictedBulletCount = output.renderedListItemCount()
            NoisyAsrResult(
                case = benchmarkCase,
                analysis = analysis,
                predictedList = predictedList,
                predictedBulletCount = predictedBulletCount,
                output = output,
            )
        }

        val truePositive = results.count { it.case.shouldFormatAsList && it.predictedList }
        val trueNegative = results.count { !it.case.shouldFormatAsList && !it.predictedList }
        val falsePositive = results.count { !it.case.shouldFormatAsList && it.predictedList }
        val falseNegative = results.count { it.case.shouldFormatAsList && !it.predictedList }
        val positives = results.filter { it.case.shouldFormatAsList }
        val exactPositiveBulletCount = positives.count { it.case.expectedBulletCount == it.predictedBulletCount }

        val passed = results.filter { it.case.shouldFormatAsList == it.predictedList && (!it.case.shouldFormatAsList || it.case.expectedBulletCount == it.predictedBulletCount) }
        val failed = results.filterNot { it in passed }

        val summary =
            """
            Deterministic List Formatter Supported Noisy ASR
            cases=${results.size}
            positives=${positives.size}
            negatives=${results.size - positives.size}
            
            confusion_matrix
              tp=$truePositive
              tn=$trueNegative
              fp=$falsePositive
              fn=$falseNegative
            
            metrics
              accuracy=${percent(truePositive + trueNegative, results.size)}%
              precision=${percent(truePositive, truePositive + falsePositive)}%
              recall=${percent(truePositive, truePositive + falseNegative)}%
              f1=${f1(truePositive, falsePositive, falseNegative)}%
              exact_positive_bullet_count_accuracy=${percent(exactPositiveBulletCount, positives.size)}%
            
            passed
            ${renderCases(passed)}
            
            failed
            ${renderCases(failed)}
            """.trimIndent()

        val reportFile = File("build/reports/formatter/list-noisy-asr.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(summary + "\n")

        println(summary)

        assertTrue("Supported noisy ASR precision below target", percent(truePositive, truePositive + falsePositive) >= MIN_PRECISION)
        assertTrue("Supported noisy ASR recall below target", percent(truePositive, truePositive + falseNegative) >= MIN_RECALL)
        assertTrue("Supported noisy ASR exact bullet-count accuracy below target", percent(exactPositiveBulletCount, positives.size) >= MIN_EXACT_BULLET_COUNT_ACCURACY)
    }

    private fun loadCases(resource: String): List<NoisyAsrCase> {
        val stream =
            javaClass.classLoader?.getResourceAsStream(resource)
                ?: error("Missing noisy ASR resource: $resource")
        val payload = stream.bufferedReader().use { it.readText() }
        val entries = JSONArray(payload)

        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                add(
                    NoisyAsrCase(
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

    private fun renderCases(results: List<NoisyAsrResult>): String {
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

    private fun reasons(result: NoisyAsrResult): String {
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

    private fun percent(numerator: Int, denominator: Int): Int {
        if (denominator == 0) return 0
        return ((numerator.toDouble() / denominator.toDouble()) * 100.0).roundToInt()
    }

    private fun f1(
        truePositive: Int,
        falsePositive: Int,
        falseNegative: Int,
    ): Int {
        val precisionDenominator = truePositive + falsePositive
        val recallDenominator = truePositive + falseNegative
        if (precisionDenominator == 0 || recallDenominator == 0) return 0
        val precision = truePositive.toDouble() / precisionDenominator.toDouble()
        val recall = truePositive.toDouble() / recallDenominator.toDouble()
        if (precision == 0.0 || recall == 0.0) return 0
        return ((2.0 * precision * recall) / (precision + recall) * 100.0).roundToInt()
    }

    private data class NoisyAsrCase(
        val id: String,
        val category: String,
        val shouldFormatAsList: Boolean,
        val expectedBulletCount: Int?,
        val text: String,
    )

    private data class NoisyAsrResult(
        val case: NoisyAsrCase,
        val analysis: DeterministicListFormatter.FormatAnalysis,
        val predictedList: Boolean,
        val predictedBulletCount: Int,
        val output: String,
    )

    companion object {
        private const val NOISY_ASR_DATASET_RESOURCE = "formatter/list_inference_noisy_asr.json"
        private const val MIN_PRECISION = 100
        private const val MIN_RECALL = 95
        private const val MIN_EXACT_BULLET_COUNT_ACCURACY = 95
    }
}
