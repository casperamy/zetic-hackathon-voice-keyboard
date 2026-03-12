package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DeterministicListFormatterParagraph100CaseTest {

    private val formatter = DeterministicListFormatter()

    @Test
    fun paragraph100CaseDatasetPasses() {
        val cases = buildCases()

        assertEquals("Paragraph dataset size changed unexpectedly", 100, cases.size)
        assertEquals("Paragraph ids must be unique", cases.size, cases.map { it.id }.toSet().size)

        val results =
            cases.map { case ->
                val output = formatter.format(case.text)
                ParagraphResult(
                    case = case,
                    output = output,
                    passed = output == case.expectedOutput,
                )
            }

        val failures = results.filterNot { it.passed }
        val passes = results.filter { it.passed }
        val categoryBreakdown =
            results.groupBy { it.case.category }
                .mapValues { (_, categoryResults) ->
                    categoryResults.count { it.passed } to categoryResults.size
                }
                .toSortedMap()

        val summary =
            buildString {
                appendLine("Deterministic List Formatter Paragraph 100 Case Dataset")
                appendLine("cases=${results.size}")
                appendLine("passed=${passes.size}")
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

        val reportFile = File("build/reports/formatter/list-paragraph-100.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(summary + "\n")

        println(summary)
        assertEquals("Paragraph 100-case dataset has failures", 0, failures.size)
    }

    private fun buildCases(): List<ParagraphCase> {
        val scenarios = scenarioSpecs()
        val prefaces = prefaces()
        val followUps = followUps()
        val protectedSentences = protectedSentences()
        val negatives = negativeParagraphs()

        return buildList {
            scenarios.forEachIndexed { index, scenario ->
                add(
                    ParagraphCase(
                        id = "P100_${index + 1}",
                        category = "sentence_run_only",
                        text = scenario.runOnlyInput(),
                        expectedOutput = scenario.listOnlyOutput(),
                    ),
                )
            }

            scenarios.forEachIndexed { index, scenario ->
                add(
                    ParagraphCase(
                        id = "P100_${index + 21}",
                        category = "plain_before_list",
                        text = "${ensureSentence(prefaces[index])} ${scenario.runOnlyInput()}",
                        expectedOutput = "${ensureSentence(prefaces[index])}\n\n${scenario.listOnlyOutput()}",
                    ),
                )
            }

            scenarios.forEachIndexed { index, scenario ->
                add(
                    ParagraphCase(
                        id = "P100_${index + 41}",
                        category = "list_then_plain",
                        text = "${scenario.runOnlyInput()} ${ensureSentence(followUps[index])}",
                        expectedOutput = "${scenario.listOnlyOutput()}\n\n${ensureSentence(followUps[index])}",
                    ),
                )
            }

            scenarios.forEachIndexed { index, scenario ->
                add(
                    ParagraphCase(
                        id = "P100_${index + 61}",
                        category = "protected_break",
                        text = "${ensureSentence(scenario.intro)} ${scenario.items.take(3).joinToString(separator = " ") { ensureSentence(it) }} ${ensureSentence(protectedSentences[index])}",
                        expectedOutput =
                            "${listOutput(normalizeIntro(scenario.intro), scenario.items.take(3))}\n\n${ensureSentence(protectedSentences[index])}",
                    ),
                )
            }

            negatives.forEachIndexed { index, negative ->
                add(
                    ParagraphCase(
                        id = "P100_${index + 81}",
                        category = "negative_plain_paragraph",
                        text = negative,
                        expectedOutput = negative,
                    ),
                )
            }
        }
    }

    private fun scenarioSpecs(): List<ScenarioSpec> {
        return listOf(
            ScenarioSpec("Here are the next steps", listOf("Freeze scope", "Rerun QA", "Ship the build", "Send notes")),
            ScenarioSpec("The priorities are clear", listOf("Reliability", "Speed", "Simplicity", "Safety")),
            ScenarioSpec("The reasons are obvious", listOf("Lower cost", "Faster setup", "Better retention")),
            ScenarioSpec("The action items are clear", listOf("Fix onboarding", "Test billing", "Review permissions", "Update screenshots")),
            ScenarioSpec("The key points are clear", listOf("Local processing", "Lower latency", "Better privacy")),
            ScenarioSpec("The benefits are clear", listOf("Cheaper hosting", "Faster typing", "Safer data handling")),
            ScenarioSpec("The problems are obvious", listOf("Long decode time", "Battery drain", "Heat")),
            ScenarioSpec("There are four things that matter", listOf("Design", "Performance", "Distribution", "Revenue")),
            ScenarioSpec("There are five things we need", listOf("Focus", "Speed", "Accuracy", "Stability", "Reach")),
            ScenarioSpec("The most important things in this app are first of all", listOf("It's local", "It's cheap", "It costs zero dollars", "It's fast", "It's private")),
            ScenarioSpec("The next steps are basically", listOf("Freeze scope", "Cut latency", "Test runtime", "Ship beta")),
            ScenarioSpec("The priorities are to be clear", listOf("Lower cost", "Better recall", "Cleaner UX")),
            ScenarioSpec("The key points are obvious", listOf("Better privacy", "Faster commits", "Smaller model")),
            ScenarioSpec("The problems are clear", listOf("CPU load", "Tail drift", "Slow decode")),
            ScenarioSpec("The benefits are obvious", listOf("Simpler stack", "Lower cost", "Easier rollout")),
            ScenarioSpec("The action items are obvious", listOf("Freeze scope", "Ship alpha", "Collect notes", "Fix bugs")),
            ScenarioSpec("The reasons are to be clear", listOf("Budget risk", "Staffing gaps", "Vendor delay")),
            ScenarioSpec("There are three key points", listOf("Lower cost", "Better privacy", "Faster setup")),
            ScenarioSpec("There are four requirements", listOf("Offline use", "Fast startup", "Plain text safety", "Accurate lists")),
            ScenarioSpec("The metrics are clear", listOf("Latency", "Accuracy", "Retention", "Conversion")),
        )
    }

    private fun prefaces(): List<String> {
        return listOf(
            "We tested the build yesterday",
            "The demo ran on the Fold all morning",
            "Our team reviewed the latest transcript",
            "The keyboard loaded without errors",
            "The app resumed after the screen rotation",
            "We recorded a new sample after lunch",
            "The current build stayed responsive",
            "The team watched the logs during the demo",
            "We compared the old flow to the new flow",
            "The product pitch was still on screen",
            "We re-opened the WhatsApp chat",
            "The device stayed plugged in during the run",
            "The benchmark report was already open",
            "The test phone stayed warm but stable",
            "We tried the same prompt twice in a row",
            "The team wrote down the last regression",
            "The local model was already initialized",
            "The debug screen still showed the last output",
            "We cleared the text box before speaking",
            "The mic button responded immediately",
        )
    }

    private fun followUps(): List<String> {
        return listOf(
            "Now we can wait",
            "After that, we need approval",
            "However, the budget is still frozen",
            "Now we can send the build",
            "Then we close the ticket",
            "After that, we review the report",
            "Now we can compare the results",
            "Then we ship the demo",
            "After that, we watch the logs",
            "Now we can stop the test",
            "However, the rollout still waits",
            "Now we can hand this to design",
            "Then we close the document",
            "After that, we record another pass",
            "Now we can move to Android Auto",
            "Then we check the Fold again",
            "However, the speech tail still repeats",
            "Now we can share the notes",
            "Then we restart the phone",
            "After that, we compare the timings",
        )
    }

    private fun protectedSentences(): List<String> {
        return listOf(
            "Email jane.doe@example.com after that",
            "The backup URL is https://api.example.com/v1/status",
            "Version 1.2.3 ships tomorrow",
            "Meet me at 500 Pine Street, Suite 400, San Francisco, California",
            "The flight is at gate B12",
            "It starts at 4:30 p.m.",
            "The appointment is on March 12, 2026",
            "The package goes to 221B Baker Street, London, NW1 6XE",
            "Ping 192.168.1.20 before shipping",
            "The budget sits at 12.5 million dollars",
            "The branch name is release/1.2.0-hotfix",
            "The serial number is SN-4421-AB",
            "The clinic is at 240 King Street, Apartment 6B, Seattle, Washington",
            "The host is api.example.com",
            "Call me at 3:45 p.m.",
            "The meeting is on Tuesday, March 3",
            "The gate moved to terminal C",
            "The suite is on floor 12",
            "The route ends at platform 4",
            "The reading changed from 3.14 to 2.71",
        )
    }

    private fun negativeParagraphs(): List<String> {
        return listOf(
            "This is the demo. It is running. It is stable. It is live.",
            "We tested yesterday. We shipped today. We will review tomorrow.",
            "The model is warm. The phone is charging. The app is idle.",
            "He opened the settings. He changed the theme. He closed the screen.",
            "They watched the graph. They waited for the build. They went home.",
            "I tried the keyboard. I paused for a second. I started again.",
            "The call ended. The room stayed quiet. The team wrote notes.",
            "She saved the file. She sent the link. She closed the tab.",
            "The update installed. The spinner stopped. The warning disappeared.",
            "We met at noon. We talked for an hour. We left early.",
            "The next steps are obvious. Now we wait.",
            "The priorities are clear. However, the budget is frozen.",
            "There are four things that matter. We should talk tomorrow.",
            "Here are the next steps. After that, we regroup.",
            "The benefits are obvious. Why is the phone still hot?",
            "The problems are clear. Because the build is stuck, we wait.",
            "The key points are clear. It was raining outside.",
            "There are three reasons we paused. Someone left early.",
            "The action items are clear. Then he restarted the phone.",
            "The reasons are obvious. The office is on 8th Avenue, Floor 12, New York, New York.",
        )
    }

    private fun ScenarioSpec.runOnlyInput(): String {
        return buildString {
            append(ensureSentence(intro))
            if (items.isNotEmpty()) {
                append(' ')
                append(items.joinToString(separator = " ") { ensureSentence(it) })
            }
        }
    }

    private fun ScenarioSpec.listOnlyOutput(): String {
        return listOutput(normalizeIntro(intro), items)
    }

    private fun listOutput(
        intro: String,
        items: List<String>,
    ): String {
        return buildString {
            append(intro)
            append(':')
            items.forEach { item ->
                append('\n')
                append(DeterministicListFormatter.BULLET_PREFIX)
                append(ensureSentence(item))
            }
        }
    }

    private fun normalizeIntro(intro: String): String {
        return intro
            .replace(Regex("""(?i)\s+(?:first of all|to be clear|basically)$"""), "")
            .trim()
            .removeSuffix(".")
    }

    private fun ensureSentence(text: String): String {
        val trimmed = text.trim()
        if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) return trimmed
        return "$trimmed."
    }

    private data class ScenarioSpec(
        val intro: String,
        val items: List<String>,
    )

    private data class ParagraphCase(
        val id: String,
        val category: String,
        val text: String,
        val expectedOutput: String,
    )

    private data class ParagraphResult(
        val case: ParagraphCase,
        val output: String,
        val passed: Boolean,
    )
}
