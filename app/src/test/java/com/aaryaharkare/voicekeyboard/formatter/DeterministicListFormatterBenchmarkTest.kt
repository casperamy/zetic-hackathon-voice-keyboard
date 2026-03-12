package com.aaryaharkare.voicekeyboard.formatter

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class DeterministicListFormatterBenchmarkTest {

    @Test
    fun benchmarkDatasetsMeetThresholds() {
        val formatter = DeterministicListFormatter()
        val datasets =
            listOf(
                BenchmarkDataset("core", CORE_DATASET_RESOURCE, expectedSize = 100),
                BenchmarkDataset("regressions", REGRESSION_DATASET_RESOURCE, expectedSize = 12),
            ).map { dataset ->
                val cases = loadCases(dataset.resource)
                assertEquals("${dataset.name} dataset size changed unexpectedly", dataset.expectedSize, cases.size)
                assertEquals("${dataset.name} ids must be unique", cases.size, cases.map { it.id }.toSet().size)
                assertTrue("${dataset.name} must contain positive and negative cases", cases.any { it.shouldFormatAsList } && cases.any { !it.shouldFormatAsList })

                DatasetReport(
                    dataset = dataset,
                    metrics = evaluateDataset(formatter, cases),
                )
            }

        val combinedMetrics = combineReports(datasets)

        val summary =
            buildString {
                appendLine("Deterministic List Formatter Benchmark")
                datasets.forEach { report ->
                    appendLine()
                    append(renderDatasetSummary(report))
                }
                appendLine()
                append(renderCombinedSummary(combinedMetrics))
            }.trim()

        val reportFile = File("build/reports/formatter/list-benchmark.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(summary + "\n")

        println(summary)

        val core = datasets.first { it.dataset.name == "core" }.metrics
        val regressions = datasets.first { it.dataset.name == "regressions" }.metrics

        assertTrue("Core precision below target", core.precision >= MIN_PRECISION)
        assertTrue("Core recall below target", core.recall >= MIN_RECALL)
        assertTrue("Core exact bullet-count accuracy below target", core.exactPositiveBulletCountAccuracy >= MIN_EXACT_BULLET_COUNT_ACCURACY)
        assertTrue("Regression dataset has failures", regressions.mismatches.isEmpty())
        assertTrue("Combined category floor violated", combinedMetrics.categoryBreakdown.values.all { it >= MIN_CATEGORY_ACCURACY })
    }

    private fun evaluateDataset(
        formatter: DeterministicListFormatter,
        cases: List<BenchmarkCase>,
    ): DatasetMetrics {
        val results = cases.map { benchmarkCase ->
            val analysis = formatter.analyze(benchmarkCase.text)
            val output = analysis.formattedText
            val predictedList = output.containsRenderedList()
            val predictedBulletCount = output.renderedListItemCount()
            BenchmarkResult(
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
        val categoryBreakdown =
            results.groupBy { it.case.category }
                .mapValues { (_, categoryResults) ->
                    percent(
                        numerator = categoryResults.count { it.case.shouldFormatAsList == it.predictedList },
                        denominator = categoryResults.size,
                    )
                }
                .toSortedMap()

        val falsePositivesByCategory =
            results.filter { !it.case.shouldFormatAsList && it.predictedList }
                .groupingBy { it.case.category }
                .eachCount()
                .toSortedMap()

        val falseNegativesByCategory =
            results.filter { it.case.shouldFormatAsList && !it.predictedList }
                .groupingBy { it.case.category }
                .eachCount()
                .toSortedMap()

        val numberedPreservationCases = results.filter { it.case.category.contains("existing_", ignoreCase = true) && it.case.shouldFormatAsList }
        val numberedPreservationPassRate =
            percent(
                numerator = numberedPreservationCases.count { it.case.expectedBulletCount == it.predictedBulletCount && it.predictedList },
                denominator = numberedPreservationCases.size,
            )

        return DatasetMetrics(
            totalCases = results.size,
            positives = positives.size,
            negatives = results.size - positives.size,
            truePositive = truePositive,
            trueNegative = trueNegative,
            falsePositive = falsePositive,
            falseNegative = falseNegative,
            accuracy = percent(truePositive + trueNegative, results.size),
            precision = percent(truePositive, truePositive + falsePositive),
            recall = percent(truePositive, truePositive + falseNegative),
            f1 = f1(truePositive, falsePositive, falseNegative),
            exactPositiveBulletCountMatches = exactPositiveBulletCount,
            exactPositiveBulletCountAccuracy = percent(exactPositiveBulletCount, positives.size),
            categoryBreakdown = categoryBreakdown,
            falsePositivesByCategory = falsePositivesByCategory,
            falseNegativesByCategory = falseNegativesByCategory,
            numberedPreservationMatches = numberedPreservationCases.count { it.case.expectedBulletCount == it.predictedBulletCount && it.predictedList },
            numberedPreservationCases = numberedPreservationCases.size,
            numberedPreservationPassRate = numberedPreservationPassRate,
            mismatches = results.filter { it.case.shouldFormatAsList != it.predictedList || (it.case.shouldFormatAsList && it.case.expectedBulletCount != null && it.case.expectedBulletCount != it.predictedBulletCount) },
        )
    }

    private fun combineReports(reports: List<DatasetReport>): DatasetMetrics {
        val allMismatches = reports.flatMap { it.metrics.mismatches }
        val allCategoryBreakdown =
            reports.flatMap { report ->
                report.metrics.categoryBreakdown.entries.map { it.key to it.value }
            }.toMap(sortedMapOf())

        return DatasetMetrics(
            totalCases = reports.sumOf { it.metrics.totalCases },
            positives = reports.sumOf { it.metrics.positives },
            negatives = reports.sumOf { it.metrics.negatives },
            truePositive = reports.sumOf { it.metrics.truePositive },
            trueNegative = reports.sumOf { it.metrics.trueNegative },
            falsePositive = reports.sumOf { it.metrics.falsePositive },
            falseNegative = reports.sumOf { it.metrics.falseNegative },
            accuracy = percent(reports.sumOf { it.metrics.truePositive + it.metrics.trueNegative }, reports.sumOf { it.metrics.totalCases }),
            precision = percent(reports.sumOf { it.metrics.truePositive }, reports.sumOf { it.metrics.truePositive + it.metrics.falsePositive }),
            recall = percent(reports.sumOf { it.metrics.truePositive }, reports.sumOf { it.metrics.truePositive + it.metrics.falseNegative }),
            f1 = f1(reports.sumOf { it.metrics.truePositive }, reports.sumOf { it.metrics.falsePositive }, reports.sumOf { it.metrics.falseNegative }),
            exactPositiveBulletCountMatches = reports.sumOf { it.metrics.exactPositiveBulletCountMatches },
            exactPositiveBulletCountAccuracy = percent(reports.sumOf { it.metrics.exactPositiveBulletCountMatches }, reports.sumOf { it.metrics.positives }),
            categoryBreakdown = allCategoryBreakdown,
            falsePositivesByCategory = reports.fold(sortedMapOf()) { acc, report ->
                report.metrics.falsePositivesByCategory.forEach { (category, count) ->
                    acc[category] = (acc[category] ?: 0) + count
                }
                acc
            },
            falseNegativesByCategory = reports.fold(sortedMapOf()) { acc, report ->
                report.metrics.falseNegativesByCategory.forEach { (category, count) ->
                    acc[category] = (acc[category] ?: 0) + count
                }
                acc
            },
            numberedPreservationMatches = reports.sumOf { it.metrics.numberedPreservationMatches },
            numberedPreservationCases = reports.sumOf { it.metrics.numberedPreservationCases },
            numberedPreservationPassRate = percent(reports.sumOf { it.metrics.numberedPreservationMatches }, reports.sumOf { it.metrics.numberedPreservationCases }),
            mismatches = allMismatches,
        )
    }

    private fun loadCases(resource: String): List<BenchmarkCase> {
        val stream =
            javaClass.classLoader?.getResourceAsStream(resource)
                ?: error("Missing benchmark resource: $resource")
        val payload = stream.bufferedReader().use { it.readText() }
        val entries = JSONArray(payload)

        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                add(
                    BenchmarkCase(
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

    private fun renderDatasetSummary(report: DatasetReport): String {
        val metrics = report.metrics
        val mismatchSection =
            if (metrics.mismatches.isEmpty()) {
                "None"
            } else {
                metrics.mismatches.joinToString(separator = "\n\n") { mismatch ->
                    buildString {
                        append(mismatch.case.id)
                        append(" [")
                        append(mismatch.case.category)
                        append("] expected=")
                        append(if (mismatch.case.shouldFormatAsList) "LIST" else "PLAIN")
                        append(" predicted=")
                        append(if (mismatch.predictedList) "LIST" else "PLAIN")
                        append(" bullets=")
                        append(mismatch.predictedBulletCount)
                        mismatch.case.expectedBulletCount?.let {
                            append("/")
                            append(it)
                        }
                        append('\n')
                        append("INPUT: ")
                        append(mismatch.case.text)
                        append('\n')
                        append("OUTPUT: ")
                        append(mismatch.output)
                        append('\n')
                        append("REASONS: ")
                        append(mismatch.reasons())
                    }
                }
            }

        return """
            dataset=${report.dataset.name}
            cases=${metrics.totalCases}
            positives=${metrics.positives}
            negatives=${metrics.negatives}
            
            confusion_matrix
              tp=${metrics.truePositive}
              tn=${metrics.trueNegative}
              fp=${metrics.falsePositive}
              fn=${metrics.falseNegative}
            
            metrics
              accuracy=${metrics.accuracy}%
              precision=${metrics.precision}%
              recall=${metrics.recall}%
              f1=${metrics.f1}%
              exact_positive_bullet_count_accuracy=${metrics.exactPositiveBulletCountAccuracy}%
              numbered_multiline_preservation=${metrics.numberedPreservationPassRate}%
            
            category_breakdown
            ${renderCountMap(metrics.categoryBreakdown)}
            
            false_positives_by_category
            ${renderCountMap(metrics.falsePositivesByCategory)}
            
            false_negatives_by_category
            ${renderCountMap(metrics.falseNegativesByCategory)}
            
            mismatches
            $mismatchSection
        """.trimIndent()
    }

    private fun renderCombinedSummary(metrics: DatasetMetrics): String {
        return """
            combined
            cases=${metrics.totalCases}
            positives=${metrics.positives}
            negatives=${metrics.negatives}
            
            metrics
              accuracy=${metrics.accuracy}%
              precision=${metrics.precision}%
              recall=${metrics.recall}%
              f1=${metrics.f1}%
              exact_positive_bullet_count_accuracy=${metrics.exactPositiveBulletCountAccuracy}%
              numbered_multiline_preservation=${metrics.numberedPreservationPassRate}%
            
            category_breakdown
            ${renderCountMap(metrics.categoryBreakdown)}
        """.trimIndent()
    }

    private fun renderCountMap(values: Map<String, Int>): String {
        if (values.isEmpty()) return "  none"
        return values.entries.joinToString(separator = "\n") { (key, value) -> "  $key: $value" }
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

    private data class BenchmarkDataset(
        val name: String,
        val resource: String,
        val expectedSize: Int,
    )

    private data class DatasetReport(
        val dataset: BenchmarkDataset,
        val metrics: DatasetMetrics,
    )

    private data class BenchmarkCase(
        val id: String,
        val category: String,
        val shouldFormatAsList: Boolean,
        val expectedBulletCount: Int?,
        val text: String,
    )

    private data class BenchmarkResult(
        val case: BenchmarkCase,
        val analysis: DeterministicListFormatter.FormatAnalysis,
        val predictedList: Boolean,
        val predictedBulletCount: Int,
        val output: String,
    ) {
        fun reasons(): String {
            val decisions = analysis.paragraphAnalyses.flatMap { it.candidateDecisions }
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
    }

    private data class DatasetMetrics(
        val totalCases: Int,
        val positives: Int,
        val negatives: Int,
        val truePositive: Int,
        val trueNegative: Int,
        val falsePositive: Int,
        val falseNegative: Int,
        val accuracy: Int,
        val precision: Int,
        val recall: Int,
        val f1: Int,
        val exactPositiveBulletCountMatches: Int,
        val exactPositiveBulletCountAccuracy: Int,
        val categoryBreakdown: Map<String, Int>,
        val falsePositivesByCategory: Map<String, Int>,
        val falseNegativesByCategory: Map<String, Int>,
        val numberedPreservationMatches: Int,
        val numberedPreservationCases: Int,
        val numberedPreservationPassRate: Int,
        val mismatches: List<BenchmarkResult>,
    )

    companion object {
        private const val CORE_DATASET_RESOURCE = "formatter/list_inference_benchmark.json"
        private const val REGRESSION_DATASET_RESOURCE = "formatter/list_inference_regressions.json"

        private const val MIN_PRECISION = 100
        private const val MIN_RECALL = 100
        private const val MIN_EXACT_BULLET_COUNT_ACCURACY = 100
        private const val MIN_CATEGORY_ACCURACY = 90
    }
}
