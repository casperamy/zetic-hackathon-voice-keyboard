package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DeterministicListFormatterNumberedRun100CaseTest {

    private val formatter = DeterministicListFormatter()

    @Test
    fun numberedRun100CaseDatasetPasses() {
        val cases = buildCases()

        assertEquals("Numbered-run dataset size changed unexpectedly", 100, cases.size)
        assertEquals("Numbered-run ids must be unique", cases.size, cases.map { it.id }.toSet().size)

        val results =
            cases.map { case ->
                val output = formatter.format(case.text)
                NumberedRunResult(
                    case = case,
                    output = output,
                    passed = output == case.expectedOutput,
                )
            }

        val failures = results.filterNot { it.passed }
        val categoryBreakdown =
            results.groupBy { it.case.category }
                .mapValues { (_, categoryResults) ->
                    categoryResults.count { it.passed } to categoryResults.size
                }
                .toSortedMap()

        val summary =
            buildString {
                appendLine("Deterministic List Formatter Numbered Run 100 Case Dataset")
                appendLine("cases=${results.size}")
                appendLine("passed=${results.count { it.passed }}")
                appendLine("failed=${failures.size}")
                appendLine()
                appendLine("category_breakdown")
                categoryBreakdown.forEach { (category, counts) ->
                    append("  ")
                    append(category)
                    append(": ")
                    append(counts.first)
                    append('/')
                    append(counts.second)
                    appendLine()
                }
                appendLine()
                appendLine("failed_cases")
                if (failures.isEmpty()) {
                    append("  none")
                } else {
                    failures.forEachIndexed { index, result ->
                        if (index > 0) appendLine().appendLine()
                        append(result.case.id)
                        append(" [")
                        append(result.case.category)
                        appendLine("]")
                        append("INPUT: ").appendLine(result.case.text)
                        append("EXPECTED: ").appendLine(result.case.expectedOutput)
                        append("OUTPUT: ").append(result.output)
                    }
                }
            }.trim()

        val reportFile = File("build/reports/formatter/list-numbered-run-100.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(summary + "\n")

        println(summary)
        assertEquals("Numbered-run 100-case dataset has failures", 0, failures.size)
    }

    private fun buildCases(): List<NumberedRunCase> {
        val scenarios = scenarioSpecs()
        val prefixes = prefixes()
        val suffixes = suffixes()
        val negatives = negativeNumericParagraphs()
        val badNumbers = inconsistentNumberSets()

        return buildList {
            scenarios.forEachIndexed { index, scenario ->
                val input =
                    scenario.items.mapIndexed { itemIndex, item ->
                        if (index % 2 == 0) {
                            "${itemIndex + 1}. $item"
                        } else {
                            "${itemIndex + 1}) $item"
                        }
                    }.joinToString(separator = "\n")

                add(
                    NumberedRunCase(
                        id = "NR100_${index + 1}",
                        category = "ordered_whole_paragraph",
                        text = input,
                        expectedOutput = renderOrderedList("", scenario.items),
                    ),
                )
            }

            scenarios.forEachIndexed { index, scenario ->
                val body = scenario.items.mapIndexed { itemIndex, item -> "${itemIndex + 1}. $item" }.joinToString(separator = "\n")
                val intro = normalizeIntro(scenario.preamble)
                add(
                    NumberedRunCase(
                        id = "NR100_${index + 21}",
                        category = "embedded_ordered",
                        text = "${ensureSentence(prefixes[index])}\n$intro:\n$body\n${ensureSentence(suffixes[index])}",
                        expectedOutput =
                            "${ensureSentence(prefixes[index])}\n\n${renderOrderedList("$intro:", scenario.items)}\n\n${ensureSentence(suffixes[index])}",
                    ),
                )
            }

            scenarios.forEachIndexed { index, scenario ->
                val numbers = badNumbers[index]
                val items = scenario.items.take(numbers.size)
                val body =
                    numbers.mapIndexed { itemIndex, number ->
                        if (index % 2 == 0) {
                            "$number. ${items[itemIndex]}"
                        } else {
                            "$number) ${items[itemIndex]}"
                        }
                    }.joinToString(separator = "\n")
                add(
                    NumberedRunCase(
                        id = "NR100_${index + 41}",
                        category = "inconsistent_fallback",
                        text = "${normalizeIntro(scenario.preamble)}:\n$body",
                        expectedOutput = renderBulletedList("${normalizeIntro(scenario.preamble)}:", items),
                    ),
                )
            }

            negatives.forEachIndexed { index, negative ->
                add(
                    NumberedRunCase(
                        id = "NR100_${index + 61}",
                        category = "negative_numeric_plain",
                        text = negative,
                        expectedOutput = negative,
                    ),
                )
            }

            scenarios.forEachIndexed { index, scenario ->
                val intro = normalizeIntro(scenario.preamble)
                val orderedRun = renderOrderedList("$intro:", scenario.items.take(3))
                val orderedBody = scenario.items.take(3).mapIndexed { itemIndex, item -> "${itemIndex + 1}. $item" }.joinToString(separator = "\n")
                val inlineSentenceBody = scenario.items.take(3).mapIndexed { itemIndex, item -> "${itemIndex + 1}. ${ensureSentence(item)}" }.joinToString(separator = " ")
                val punctuatedItems = scenario.items.take(3).map(::ensureSentence)
                val edgeText =
                    when (index % 4) {
                        0 ->
                            "${ensureSentence(prefixes[index])}\n$intro:\n$orderedBody\n${ensureSentence(suffixes[index])}"
                        1 ->
                            "$intro:\n$orderedBody\nThe backup URL is https://api.example.com/v1/status."
                        2 ->
                            "${ensureSentence(prefixes[index])} $intro: $orderedBody"
                        else ->
                            "$intro: $inlineSentenceBody ${ensureSentence(suffixes[index])}"
                    }

                val expected =
                    when (index % 4) {
                        0 -> "${ensureSentence(prefixes[index])}\n\n$orderedRun\n\n${ensureSentence(suffixes[index])}"
                        1 -> "$orderedRun\n\nThe backup URL is https://api.example.com/v1/status."
                        2 -> "${ensureSentence(prefixes[index])}\n\n$orderedRun"
                        else -> "${renderOrderedList("$intro:", punctuatedItems)}\n\n${ensureSentence(suffixes[index])}"
                    }

                add(
                    NumberedRunCase(
                        id = "NR100_${index + 81}",
                        category = "mixed_edge_cases",
                        text = edgeText,
                        expectedOutput = expected,
                    ),
                )
            }
        }
    }

    private fun scenarioSpecs(): List<ScenarioSpec> {
        return listOf(
            ScenarioSpec("There are 5 aspects to this", listOf("Normal", "Expert", "Developer", "Product Manager", "CEO")),
            ScenarioSpec("There are 4 priorities for launch", listOf("Design", "Testing", "Distribution", "Support")),
            ScenarioSpec("There are 3 key points to cover", listOf("Privacy", "Latency", "Accuracy")),
            ScenarioSpec("There are 4 risks to watch", listOf("Battery drain", "Tail drift", "Heat", "Memory pressure")),
            ScenarioSpec("There are 3 phases in the rollout", listOf("Pilot", "Beta", "Public launch")),
            ScenarioSpec("There are 4 goals for the week", listOf("Freeze scope", "Lower latency", "Ship alpha", "Collect feedback")),
            ScenarioSpec("There are 5 teams in the review", listOf("Design", "Engineering", "Product", "Growth", "Sales")),
            ScenarioSpec("There are 3 questions to answer", listOf("Who buys", "Why now", "What changed")),
            ScenarioSpec("There are 4 operating modes", listOf("Normal", "Expert", "Developer", "CEO")),
            ScenarioSpec("There are 3 ways to validate this", listOf("Benchmark it", "Test it live", "Review the logs")),
            ScenarioSpec("There are 5 checklist items here", listOf("Record input", "Decode speech", "Format output", "Verify text", "Ship build")),
            ScenarioSpec("There are 4 steps in setup", listOf("Install app", "Enable IME", "Grant mic", "Run demo")),
            ScenarioSpec("There are 3 customer groups", listOf("Students", "Founders", "Operators")),
            ScenarioSpec("There are 5 examples to compare", listOf("Email", "Address", "Version", "Title", "Clause chain")),
            ScenarioSpec("There are 4 themes in the pitch", listOf("Speed", "Privacy", "Cost", "Simplicity")),
            ScenarioSpec("There are 3 fixes in scope", listOf("Ordered lists", "Sentence runs", "Decode caps")),
            ScenarioSpec("There are 5 benefits in play", listOf("Lower cost", "Faster setup", "Better privacy", "Cleaner output", "Safer rollouts")),
            ScenarioSpec("There are 4 blockers today", listOf("CPU heat", "Slow decode", "ASR drift", "Runtime misses")),
            ScenarioSpec("There are 3 demo tracks", listOf("Product story", "Technical depth", "Live test")),
            ScenarioSpec("There are 5 output views available", listOf("Normal", "Expert", "Developer", "Product Manager", "CEO")),
        )
    }

    private fun prefixes(): List<String> {
        return listOf(
            "We tested the latest build this morning",
            "The keyboard was already open on the Fold",
            "Our team reviewed the runtime logs before speaking",
            "The demo call started a few minutes ago",
            "We cleared the text box before trying again",
            "The product pitch was still visible on screen",
            "We compared the old formatter to the new one",
            "The current branch already had the sentence-run fix",
            "The app stayed responsive during the recording",
            "The device remained plugged in for the full demo",
            "The team wrote down the previous regression",
            "We reopened the WhatsApp thread before testing",
            "The last transcript was still visible in the field",
            "The benchmark report stayed open during the pass",
            "We tried the same phrase twice in a row",
            "The team watched the debug trace carefully",
            "The app resumed after the screen rotated",
            "We captured the output before clearing the field",
            "The microphone button responded immediately",
            "The thermal warning never appeared during this pass",
        )
    }

    private fun suffixes(): List<String> {
        return listOf(
            "Now we can review the output",
            "After that, we can close the test",
            "Then we compare the results",
            "Now we can move to the next prompt",
            "After that, we send the report",
            "Then we stop the recording",
            "Now we can look at the logs",
            "After that, we check the field again",
            "Then we ship the build",
            "Now we can wait",
            "After that, we archive the notes",
            "Then we restart the phone",
            "Now we can move to Android Auto",
            "After that, we compare the timings",
            "Then we share the report",
            "Now we can close the document",
            "After that, we test the next case",
            "Then we gather more feedback",
            "Now we can finish the demo",
            "After that, we talk through the misses",
        )
    }

    private fun inconsistentNumberSets(): List<List<Int>> {
        return listOf(
            listOf(1, 2, 4),
            listOf(2, 3, 4),
            listOf(1, 1, 2),
            listOf(1, 3, 4),
            listOf(3, 4, 5),
            listOf(1, 2, 2),
            listOf(1, 4, 5),
            listOf(2, 4, 6),
            listOf(1, 3, 5),
            listOf(5, 6, 7),
            listOf(1, 2, 5),
            listOf(4, 5, 6),
            listOf(1, 1, 3),
            listOf(2, 2, 3),
            listOf(1, 4, 6),
            listOf(3, 3, 4),
            listOf(1, 5, 6),
            listOf(6, 7, 8),
            listOf(2, 3, 5),
            listOf(1, 2, 7),
        )
    }

    private fun negativeNumericParagraphs(): List<String> {
        return listOf(
            "Version 1.2.3 shipped before 2.0.1 and the hotfix landed later.",
            "The meeting starts at 3:45 p.m. on March 11, 2026.",
            "The office is at 500 Pine Street, Suite 400, San Francisco, California.",
            "The reading changed from 3.14 to 2.71 after calibration.",
            "Ping 192.168.1.20 before you call support.",
            "The package goes to 221B Baker Street, London, NW1 6XE.",
            "The host is api.example.com and the path is /v1/status.",
            "The branch name is release/1.2.0-hotfix and it stays frozen.",
            "The gate moved to terminal B and platform 4 stays closed.",
            "Email jane.doe@example.com after the review ends.",
            "The receipt shows 12.5 dollars and 3.2 hours.",
            "The serial number is SN-4421-AB and the model is K2.",
            "The route ends at platform 7 after stop 3.",
            "The report covers Q1 through Q3 without any numbered list markers.",
            "The apartment is 6B on floor 12 and the buzzer is 4.",
            "The release date is 03/11/2026 and the rollback date is 03/12/2026.",
            "The flight arrives at gate C12 at 9:10 a.m.",
            "The dashboard shows 1.0, 1.1, and 1.2 in one chart.",
            "The score changed from 2.4 to 2.8 after the patch.",
            "The title is Director, Product Strategy, for the regional team.",
        )
    }

    private fun renderOrderedList(
        preamble: String,
        items: List<String>,
    ): String {
        return buildString {
            if (preamble.isNotBlank()) {
                append(preamble)
                append('\n')
            }
            items.forEachIndexed { index, item ->
                if (index > 0) append('\n')
                append(index + 1)
                append(". ")
                append(item)
            }
        }
    }

    private fun renderBulletedList(
        preamble: String,
        items: List<String>,
    ): String {
        return buildString {
            if (preamble.isNotBlank()) {
                append(preamble)
                append('\n')
            }
            items.forEachIndexed { index, item ->
                if (index > 0) append('\n')
                append(DeterministicListFormatter.BULLET_PREFIX)
                append(item)
            }
        }
    }

    private fun normalizeIntro(preamble: String): String = preamble.removeSuffix(".").removeSuffix(":").trim()

    private fun ensureSentence(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) trimmed else "$trimmed."
    }

    private data class ScenarioSpec(
        val preamble: String,
        val items: List<String>,
    )

    private data class NumberedRunCase(
        val id: String,
        val category: String,
        val text: String,
        val expectedOutput: String,
    )

    private data class NumberedRunResult(
        val case: NumberedRunCase,
        val output: String,
        val passed: Boolean,
    )
}
