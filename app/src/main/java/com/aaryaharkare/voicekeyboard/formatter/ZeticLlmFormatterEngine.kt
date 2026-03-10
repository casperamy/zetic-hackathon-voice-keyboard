package com.aaryaharkare.voicekeyboard.formatter

import android.content.Context
import com.zeticai.mlange.core.model.llm.LLMModelMode
import com.zeticai.mlange.core.model.llm.ZeticMLangeLLMModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ZeticLlmFormatterEngine(
    context: Context,
    private val personalKey: String,
    private val formatterModel: String,
    private val confidenceThreshold: Double = FormatterDecisionPolicy.DEFAULT_CONFIDENCE_THRESHOLD,
) : FormatterEngine {
    private val applicationContext = context.applicationContext
    private val generationMutex = Mutex()

    @Volatile
    private var model: ZeticMLangeLLMModel? = null

    override suspend fun format(
        rawText: String,
        fieldContext: FormatterFieldContext,
    ): FormatterResult = withContext(Dispatchers.Default) {
        val normalizedRawText = rawText.trim()
        if (normalizedRawText.isBlank()) {
            return@withContext FormatterResult(
                finalText = "",
                confidence = 0.0,
                rawText = "",
                formattedText = "",
                changesApplied = false,
                debugReason = "empty_raw_text",
            )
        }

        if (formatterModel.isBlank()) {
            return@withContext FormatterResult(
                finalText = normalizedRawText,
                confidence = 0.0,
                rawText = normalizedRawText,
                formattedText = normalizedRawText,
                changesApplied = false,
                debugReason = "formatter_model_not_configured",
            )
        }

        generationMutex.withLock {
            try {
                val llmModel = getOrCreateModel()

                val cleanupStartedAt = System.nanoTime()
                llmModel.run(buildCleanupPrompt(normalizedRawText))
                val cleanupRawOutput = consumeModelOutput(llmModel)
                val cleanupParsed = parseCleanupOutput(cleanupRawOutput)
                val cleanupText = cleanupParsed?.cleanedText?.ifBlank { normalizedRawText } ?: normalizedRawText
                val cleanupMs = nanosToMillis(System.nanoTime() - cleanupStartedAt)
                runCatching { llmModel.cleanUp() }

                val listStartedAt = System.nanoTime()
                llmModel.run(buildListFormattingPrompt(cleanupText, fieldContext))
                val initialListRawOutput = consumeModelOutput(llmModel)
                val parsed = FormatterOutputParser.parse(initialListRawOutput)
                var listMs = nanosToMillis(System.nanoTime() - listStartedAt)
                runCatching { llmModel.cleanUp() }

                if (parsed == null) {
                    return@withLock FormatterResult(
                        finalText = normalizedRawText,
                        confidence = 0.0,
                        rawText = normalizedRawText,
                        formattedText = "",
                        changesApplied = false,
                        debugReason = "formatter_output_parse_failed cpass=${cleanupMs}ms lpass=${listMs}ms",
                        cleanupPassMs = cleanupMs,
                        listPassMs = listMs,
                        cleanupRawOutput = cleanupRawOutput,
                        listRawOutput = initialListRawOutput,
                        cleanupText = cleanupText,
                    )
                }

                var effectiveFormattedText = parsed.formattedText
                var effectiveConfidence = parsed.confidence
                var listRawOutput = initialListRawOutput
                var listEnforceState = "none"

                if (parsed.hasList && !looksLikeBulletList(parsed.formattedText)) {
                    val enforceStartedAt = System.nanoTime()
                    llmModel.run(buildListEnforcementPrompt(cleanupText, parsed.formattedText))
                    val enforceRawOutput = consumeModelOutput(llmModel)
                    val enforceParsed = parseFormattedOnlyOutput(enforceRawOutput)
                    listMs += nanosToMillis(System.nanoTime() - enforceStartedAt)
                    runCatching { llmModel.cleanUp() }

                    listRawOutput += "\n--- list_enforce ---\n$enforceRawOutput"
                    if (enforceParsed != null && looksLikeBulletList(enforceParsed.formattedText)) {
                        effectiveFormattedText = enforceParsed.formattedText
                        effectiveConfidence = maxOf(effectiveConfidence, enforceParsed.confidence)
                        listEnforceState = "applied"
                    } else {
                        listEnforceState = "failed"
                    }
                }

                var decision =
                    FormatterDecisionPolicy.chooseFinalText(
                        rawText = normalizedRawText,
                        formattedText = effectiveFormattedText,
                        confidence = effectiveConfidence,
                        hasList = parsed.hasList || looksLikeBulletList(effectiveFormattedText),
                        needsFormatting = parsed.needsFormatting || looksLikeBulletList(effectiveFormattedText),
                        threshold = confidenceThreshold,
                    )

                val cleanupConfidence = cleanupParsed?.confidence ?: 0.0
                if (decision.reason == "low_confidence_fallback" &&
                    cleanupConfidence >= CLEANUP_CONFIDENCE_THRESHOLD &&
                    cleanupText != normalizedRawText &&
                    cleanupText.isNotBlank()
                ) {
                    decision =
                        FormatterDecisionPolicy.CommitDecision(
                            finalText = cleanupText,
                            changesApplied = true,
                            reason = "low_confidence_fallback_cleanup_applied",
                        )
                }

                val extraDebug =
                    " list=${parsed.hasList}:${parsed.listType ?: "-"}" +
                        " fmt=${parsed.needsFormatting}" +
                        " corr=${parsed.needsCorrection}" +
                        " fixes=${parsed.suspectedWordFixes.size}" +
                        " enforce=${listEnforceState}" +
                        " cpass=${cleanupMs}ms" +
                        " lpass=${listMs}ms"

                return@withLock FormatterResult(
                    finalText = decision.finalText,
                    confidence = effectiveConfidence,
                    rawText = normalizedRawText,
                    formattedText = effectiveFormattedText,
                    changesApplied = decision.changesApplied,
                    debugReason = "${decision.reason}$extraDebug",
                    cleanupPassMs = cleanupMs,
                    listPassMs = listMs,
                    cleanupRawOutput = cleanupRawOutput,
                    listRawOutput = listRawOutput,
                    cleanupText = cleanupText,
                )
            } catch (t: Throwable) {
                return@withLock FormatterResult(
                    finalText = normalizedRawText,
                    confidence = 0.0,
                    rawText = normalizedRawText,
                    formattedText = normalizedRawText,
                    changesApplied = false,
                    debugReason = "formatter_runtime_error:${t.message ?: "unknown"}",
                )
            } finally {
                runCatching { model?.cleanUp() }
            }
        }
    }

    fun deinit() {
        synchronized(this) {
            runCatching { model?.deinit() }
            model = null
        }
    }

    private fun getOrCreateModel(): ZeticMLangeLLMModel {
        val existing = model
        if (existing != null) return existing

        return synchronized(this) {
            val again = model
            if (again != null) {
                again
            } else {
                ZeticMLangeLLMModel(
                    context = applicationContext,
                    personalKey = personalKey,
                    name = formatterModel,
                    modelMode = LLMModelMode.RUN_AUTO,
                ).also { model = it }
            }
        }
    }

    private fun consumeModelOutput(model: ZeticMLangeLLMModel): String {
        val output = StringBuilder()
        var step = 0
        while (step < MAX_GENERATION_STEPS && output.length < MAX_OUTPUT_CHARS) {
            val next = model.waitForNextToken()
            if (next.generatedTokens == 0) {
                break
            }
            if (next.token.isNotEmpty()) {
                output.append(next.token)
            }
            step += 1
        }
        return output.toString().trim()
    }

    private fun buildCleanupPrompt(rawText: String): String {
        return """
You are pass 1 of a speech formatter pipeline.
Task: remove speech disfluencies/filler noise while keeping coherent meaning.

Rules:
1) Preserve meaning exactly.
2) Remove filler/disfluency tokens like: "um", "uh", "you know", "like" (when filler), repeated stutter fragments, repeated false starts.
3) Remove obvious self-corrections noise like "no no no", "an ananana", repeated partial words.
4) Ensure the output remains coherent natural text after cleanup. Light punctuation/casing repair is allowed only for readability.
5) Keep key content words; do not drop intent-bearing nouns/verbs.
6) Remove meta/self-talk not intended for typing (thinking aloud, side comments, process remarks).
7) Remove irrelevant fragments that are clearly not part of intended final text.
8) Do not convert to bullet lists in this pass.
9) Do not add facts.
10) Return JSON only with keys: cleaned_text, confidence, removed_fillers.
11) confidence must be 0..1.

Examples:
Input: "um okay we should like buy milk uh eggs and bread"
Output cleaned_text: "Okay, we should buy milk, eggs, and bread."

Input: "an ananana no no no the point is finish the report today"
Output cleaned_text: "The point is to finish the report today."

Input: "plants animals species yeah animals we complete them these words are irrelevant"
Output cleaned_text: "plants animals species"

Transcript:
$rawText
        """.trimIndent()
    }

    private fun buildListFormattingPrompt(
        rawText: String,
        fieldContext: FormatterFieldContext,
    ): String {
        return """
You are pass 2 of a speech formatter pipeline.
Task: paragraph/list structure formatting.

Rules:
1) Preserve meaning exactly. Do not summarize. Do not add facts.
2) Improve punctuation, spacing, casing, paragraph breaks, and LIST formatting.
3) Focus on list inference and structure. Do not perform broad rewriting.
4) Return JSON only. No markdown, no prose, no code fences.
5) Required keys: has_list, list_type, needs_formatting, needs_correction, suspected_word_fixes, formatted_text, confidence.
6) confidence must be between 0 and 1.
7) If there is a narrative sentence, then a list, then another sentence, keep that structure.
8) If list is inferred, format items as bullet points using "- " one per line.
9) If item-like sequence has 3+ entries, treat it as a list even when grammar is fragmented.
10) If mixed structure exists (sentence + list + sentence), preserve that mixed structure.

List inference guidance:
- Infer list when utterance contains a sequence of noun phrases/items separated by commas, "and", "or", or spoken fragments.
- Infer list even if grammar is not a coherent sentence.
- Prefer list formatting over forcing one long sentence when structure is item-like.
- When uncertain between a run-on sentence and a list, prefer list output if there are 3+ candidate items.

Examples:
Input: "okay before things are plants animals things and species"
Output formatted_text:
Before things:
- plants
- animals
- things
- species

Input: "we should buy milk eggs bread and soap then call mom"
Output formatted_text:
We should buy:
- milk
- eggs
- bread
- soap
Then call mom.

Input context:
- multiline_field: ${fieldContext.isMultiline}
- input_type: ${fieldContext.inputType}

Transcript:
$rawText
        """.trimIndent()
    }

    private fun buildListEnforcementPrompt(
        cleanupText: String,
        currentFormattedText: String,
    ): String {
        return """
You are a strict list enforcer in pass 2.
Task: keep meaning exactly, but ensure list content is rendered as bullet points.

Rules:
1) Do not add facts. Do not summarize.
2) Preserve narrative text before/after the list.
3) Every list item must be on its own line and start with "- ".
4) If there is no list content, return the current formatted text unchanged.
5) Return JSON only with keys: formatted_text, confidence.
6) confidence must be 0..1.

Intended text:
$cleanupText

Current formatted text:
$currentFormattedText
        """.trimIndent()
    }

    private fun parseCleanupOutput(rawOutput: String): ParsedCleanupPayload? {
        val jsonPayload = extractJsonObject(rawOutput) ?: return null
        return runCatching {
            val parsed = JSONObject(jsonPayload)
            val cleanedText = parsed.optString("cleaned_text", "").trim()
            if (cleanedText.isBlank()) return@runCatching null
            ParsedCleanupPayload(
                cleanedText = cleanedText,
                confidence = normalizeConfidence(parsed.opt("confidence")),
            )
        }.getOrNull()
    }

    private fun parseFormattedOnlyOutput(rawOutput: String): ParsedFormattedOnlyPayload? {
        val jsonPayload = extractJsonObject(rawOutput) ?: return null
        return runCatching {
            val parsed = JSONObject(jsonPayload)
            val formattedText = parsed.optString("formatted_text", "").trim()
            if (formattedText.isBlank()) return@runCatching null
            ParsedFormattedOnlyPayload(
                formattedText = formattedText,
                confidence = normalizeConfidence(parsed.opt("confidence")),
            )
        }.getOrNull()
    }

    private fun extractJsonObject(rawOutput: String): String? {
        val trimmed = rawOutput.trim()
        if (trimmed.isEmpty()) return null
        val firstBrace = trimmed.indexOf('{')
        if (firstBrace == -1) return null

        var depth = 0
        var endIndex = -1
        for (i in firstBrace until trimmed.length) {
            when (trimmed[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        endIndex = i
                        break
                    }
                }
            }
        }
        if (endIndex <= firstBrace) return null
        return trimmed.substring(firstBrace, endIndex + 1)
    }

    private fun normalizeConfidence(value: Any?): Double {
        val parsed =
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            } ?: 0.0
        val normalized = if (parsed > 1.0 && parsed <= 100.0) parsed / 100.0 else parsed
        return normalized.coerceIn(0.0, 1.0)
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    private fun looksLikeBulletList(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return false
        return lines.count { it.startsWith("- ") || it.startsWith("* ") || it.matches(Regex("""\d+\.\s+.+""")) } >= 2
    }

    companion object {
        private const val MAX_GENERATION_STEPS = 1_024
        private const val MAX_OUTPUT_CHARS = 6_000
        private const val CLEANUP_CONFIDENCE_THRESHOLD = 0.55
    }

    private data class ParsedCleanupPayload(
        val cleanedText: String,
        val confidence: Double,
    )

    private data class ParsedFormattedOnlyPayload(
        val formattedText: String,
        val confidence: Double,
    )
}
