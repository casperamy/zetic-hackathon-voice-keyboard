package com.aaryaharkare.voicekeyboard.formatter

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DeterministicListFormatterParagraphBoundaryTest {

    @Test
    fun paragraphBoundaryDatasetPasses() {
        val formatter = DeterministicListFormatter()
        val cases = loadCases(DATASET_RESOURCE)

        assertEquals("Paragraph boundary dataset size changed unexpectedly", 8, cases.size)
        assertEquals("Paragraph boundary ids must be unique", cases.size, cases.map { it.id }.toSet().size)

        val failures =
            cases.map { case ->
                case to formatter.format(case.text)
            }.filter { (case, output) ->
                output != case.expectedOutput
            }

        val summary =
            buildString {
                appendLine("Deterministic List Formatter Paragraph Boundaries")
                appendLine("cases=${cases.size}")
                appendLine("failures=${failures.size}")
                appendLine()
                if (failures.isEmpty()) {
                    append("None")
                } else {
                    failures.forEachIndexed { index, (case, output) ->
                        if (index > 0) appendLine().appendLine()
                        append(case.id)
                        appendLine()
                        append("INPUT: ").appendLine(case.text)
                        append("EXPECTED: ").appendLine(case.expectedOutput)
                        append("OUTPUT: ").append(output)
                    }
                }
            }.trim()

        val reportFile = File("build/reports/formatter/list-paragraph-boundaries.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(summary + "\n")

        println(summary)
        assertEquals("Paragraph boundary dataset has failures", 0, failures.size)
    }

    private fun loadCases(resource: String): List<BoundaryCase> {
        val stream =
            javaClass.classLoader?.getResourceAsStream(resource)
                ?: error("Missing paragraph boundary resource: $resource")
        val payload = stream.bufferedReader().use { it.readText() }
        val entries = JSONArray(payload)

        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                add(
                    BoundaryCase(
                        id = item.getString("id"),
                        text = item.getString("text"),
                        expectedOutput = item.getString("expectedOutput"),
                    ),
                )
            }
        }
    }

    private data class BoundaryCase(
        val id: String,
        val text: String,
        val expectedOutput: String,
    )

    companion object {
        private const val DATASET_RESOURCE = "formatter/list_inference_paragraph_boundaries.json"
    }
}
