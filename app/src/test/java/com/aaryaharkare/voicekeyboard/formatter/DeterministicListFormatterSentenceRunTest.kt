package com.aaryaharkare.voicekeyboard.formatter

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DeterministicListFormatterSentenceRunTest {

    @Test
    fun sentenceRunDatasetPasses() {
        val formatter = DeterministicListFormatter()
        val cases = loadCases(DATASET_RESOURCE)

        assertEquals("Sentence-run dataset size changed unexpectedly", 10, cases.size)
        assertEquals("Sentence-run ids must be unique", cases.size, cases.map { it.id }.toSet().size)

        val failures =
            cases.map { case ->
                case to formatter.formatText(case.text)
            }.filter { (case, output) ->
                output != case.expectedOutput
            }

        val summary =
            buildString {
                appendLine("Deterministic List Formatter Sentence Run")
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

        val reportFile = File("build/reports/formatter/list-sentence-run.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(summary + "\n")

        println(summary)
        assertEquals("Sentence-run dataset has failures", 0, failures.size)
    }

    private fun loadCases(resource: String): List<SentenceRunCase> {
        val stream =
            javaClass.classLoader?.getResourceAsStream(resource)
                ?: error("Missing sentence-run resource: $resource")
        val payload = stream.bufferedReader().use { it.readText() }
        val entries = JSONArray(payload)

        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                add(
                    SentenceRunCase(
                        id = item.getString("id"),
                        text = item.getString("text"),
                        expectedOutput = item.getString("expectedOutput"),
                    ),
                )
            }
        }
    }

    private data class SentenceRunCase(
        val id: String,
        val text: String,
        val expectedOutput: String,
    )

    companion object {
        private const val DATASET_RESOURCE = "formatter/list_inference_sentence_run.json"
    }
}
