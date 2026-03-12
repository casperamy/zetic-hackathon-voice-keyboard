package com.aaryaharkare.voicekeyboard.formatter

import android.content.Context
import com.aaryaharkare.voicekeyboard.BuildConfig

class ZeticLlmFormatter(
    private val context: Context,
) : FormatterEngine {

    override suspend fun format(
        text: String,
        fieldContext: FormatterFieldContext,
        asrHints: AsrHints?,
    ): FormatterResult {
        val rawText = text.trim()
        require(rawText.isNotEmpty()) {
            "Formatter LLM received blank Whisper text"
        }

        val protectedSpans = ProtectedSpanDetector.detect(rawText)
        val passMetrics = mutableListOf<FormatterPassMetrics>()
        val debugTrace =
            mutableListOf(
                "formatter_mode=MULTI_PASS_LLM",
                "model_key=${BuildConfig.FORMATTER_LLM_MODEL}",
                "protected_spans=${protectedSpans.size}",
                "field_multiline=${fieldContext.isMultiline}",
                "field_input_type=${fieldContext.inputType}",
            )

        var totalPromptTokens = 0
        var totalGeneratedTokens = 0
        var totalGenerationMs = 0L
        var fallbackReason: String? = null

        val cleanupGeneration =
            FormatterLlmPipeline.generate(
                context = context,
                prompt = buildCleanupPrompt(rawText, protectedSpans),
            )
        totalPromptTokens += cleanupGeneration.promptTokens
        totalGeneratedTokens += cleanupGeneration.generatedTokens
        totalGenerationMs += cleanupGeneration.generationMs

        val cleanupResult =
            FormatterPassValidator.validateCleanup(
                rawText = rawText,
                payload = FormatterJsonParser.parseCleanup(cleanupGeneration.output),
                protectedSpans = protectedSpans,
            )
        passMetrics +=
            FormatterPassMetrics(
                name = PASS_CLEANUP,
                accepted = cleanupResult.accepted,
                reason = cleanupResult.reason,
                confidence = cleanupResult.confidence,
                promptTokens = cleanupGeneration.promptTokens,
                generatedTokens = cleanupGeneration.generatedTokens,
                generationMs = cleanupGeneration.generationMs,
            )
        debugTrace += buildPassTrace(PASS_CLEANUP, cleanupResult, cleanupGeneration)
        if (!cleanupResult.accepted) {
            fallbackReason = cleanupResult.reason
        }

        val pass1Text = cleanupResult.text
        val correctionGeneration =
            FormatterLlmPipeline.generate(
                context = context,
                prompt = buildCorrectionPrompt(pass1Text, protectedSpans, fieldContext, asrHints),
            )
        totalPromptTokens += correctionGeneration.promptTokens
        totalGeneratedTokens += correctionGeneration.generatedTokens
        totalGenerationMs += correctionGeneration.generationMs

        val correctionResult =
            FormatterPassValidator.validateCorrection(
                baselineText = pass1Text,
                payload = FormatterJsonParser.parseCorrection(correctionGeneration.output),
                protectedSpans = protectedSpans,
            )
        passMetrics +=
            FormatterPassMetrics(
                name = PASS_CORRECTION,
                accepted = correctionResult.accepted,
                reason = correctionResult.reason,
                confidence = correctionResult.confidence,
                promptTokens = correctionGeneration.promptTokens,
                generatedTokens = correctionGeneration.generatedTokens,
                generationMs = correctionGeneration.generationMs,
            )
        debugTrace += buildPassTrace(PASS_CORRECTION, correctionResult, correctionGeneration)
        if (!correctionResult.accepted && fallbackReason == null) {
            fallbackReason = correctionResult.reason
        }

        val pass2Text = correctionResult.text
        val surfaceSignal = ListSurfaceDetector.detect(pass2Text)
        debugTrace += "pass=$PASS_LIST surface=${surfaceSignal.reason} should_run=${surfaceSignal.shouldRun}"

        val acceptedPlans: List<ListPlan>
        if (surfaceSignal.shouldRun) {
            val listGeneration =
                FormatterLlmPipeline.generate(
                    context = context,
                    prompt = buildListPlanningPrompt(pass2Text, protectedSpans, fieldContext),
                )
            totalPromptTokens += listGeneration.promptTokens
            totalGeneratedTokens += listGeneration.generatedTokens
            totalGenerationMs += listGeneration.generationMs

            val listResolution =
                FormatterPassValidator.resolveListPlans(
                    text = pass2Text,
                    payload = FormatterJsonParser.parseListPlanning(listGeneration.output),
                    protectedSpans = protectedSpans,
                )

            passMetrics +=
                FormatterPassMetrics(
                    name = PASS_LIST,
                    accepted = listResolution.acceptedPlans.isNotEmpty(),
                    reason = listResolution.reason,
                    confidence = listResolution.confidence,
                    promptTokens = listGeneration.promptTokens,
                    generatedTokens = listGeneration.generatedTokens,
                    generationMs = listGeneration.generationMs,
                )
            debugTrace +=
                "pass=$PASS_LIST accepted=${listResolution.acceptedPlans.size} reason=${listResolution.reason} " +
                    "confidence=${listResolution.confidence?.formatConfidence() ?: "-"} " +
                    "prompt_tokens=${listGeneration.promptTokens} generated_tokens=${listGeneration.generatedTokens} " +
                    "generation_ms=${listGeneration.generationMs}"
            listResolution.acceptedPlans.forEachIndexed { index, plan ->
                debugTrace +=
                    "pass=$PASS_LIST plan=${index + 1} type=${plan.listType.name.lowercase()} " +
                        "evidence=${plan.evidenceType.name.lowercase()} confidence=${plan.confidence.formatConfidence()} " +
                        "items=${plan.items.joinToString(prefix = "[", postfix = "]")}"
            }

            acceptedPlans = listResolution.acceptedPlans
            if (acceptedPlans.isEmpty() && fallbackReason == null) {
                fallbackReason = listResolution.reason
            }
        } else {
            passMetrics +=
                FormatterPassMetrics(
                    name = PASS_LIST,
                    accepted = false,
                    reason = "list_skipped_${surfaceSignal.reason}",
                    confidence = null,
                    promptTokens = 0,
                    generatedTokens = 0,
                    generationMs = 0L,
                )
            acceptedPlans = emptyList()
        }

        val finalText =
            if (acceptedPlans.isEmpty()) {
                pass2Text
            } else {
                ListPlanRenderer.render(pass2Text, acceptedPlans)
            }

        return FormatterResult(
            formattedText = finalText,
            mode = FormatterMode.MULTI_PASS_LLM,
            debugTrace = debugTrace,
            modelKey = BuildConfig.FORMATTER_LLM_MODEL,
            promptTokens = totalPromptTokens,
            generatedTokens = totalGeneratedTokens,
            generationMs = totalGenerationMs,
            rawText = rawText,
            pass1Text = pass1Text,
            pass2Text = pass2Text,
            listPlans = acceptedPlans,
            passMetrics = passMetrics,
            fallbackReason = fallbackReason,
        )
    }

    private fun buildPassTrace(
        passName: String,
        result: PassTextResult,
        generation: FormatterLlmPipeline.FormatterLlmGenerationResult,
    ): String {
        return buildString {
            append("pass=").append(passName)
            append(" accepted=").append(result.accepted)
            append(" reason=").append(result.reason)
            append(" confidence=").append(result.confidence?.formatConfidence() ?: "-")
            append(" prompt_tokens=").append(generation.promptTokens)
            append(" generated_tokens=").append(generation.generatedTokens)
            append(" generation_ms=").append(generation.generationMs)
        }
    }

    private fun Double.formatConfidence(): String = String.format(java.util.Locale.US, "%.2f", this)

    companion object {
        internal fun buildWarmupPrompt(rawText: String): String {
            return buildCleanupPrompt(rawText, emptyList())
        }

        internal fun buildCleanupPrompt(
            rawText: String,
            protectedSpans: List<ProtectedSpan>,
        ): String {
            return """
<task>
You are pass 1 of a speech formatter pipeline for a keyboard.
Remove filler/disfluency noise only.
</task>
<rules>
- Preserve meaning and word order whenever possible.
- Remove clear filled pauses, false starts, stutters, repetitions, and self-repairs.
- Remove words like "like", "well", "so", "right", "okay", "actually", "basically" only when they are clearly nonsemantic filler.
- Do not make lists, paragraph breaks, or broad rewrites.
- Do not touch protected spans.
- Return JSON only: {"cleaned_text":"","removed_segments":[],"confidence":0.0}
</rules>
<protected_spans>
${ProtectedSpanDetector.toPromptLines(protectedSpans)}
</protected_spans>
<examples>
Input: "um okay we should buy milk eggs and bread"
Output: {"cleaned_text":"we should buy milk eggs and bread","removed_segments":["um","okay"],"confidence":0.86}
Input: "I like apples and pears"
Output: {"cleaned_text":"I like apples and pears","removed_segments":[],"confidence":0.91}
</examples>
<input_text>
$rawText
</input_text>
            """.trimIndent()
        }

        internal fun buildCorrectionPrompt(
            cleanedText: String,
            protectedSpans: List<ProtectedSpan>,
            fieldContext: FormatterFieldContext,
            asrHints: AsrHints?,
        ): String {
            val hintLines =
                asrHints?.alternativePhrases
                    ?.filter { it.isNotBlank() }
                    ?.joinToString(separator = "\n") { "- $it" }
                    ?: "- none"

            return """
<task>
You are pass 2 of a speech formatter pipeline for a keyboard.
Fix only obvious context-dependent word errors.
</task>
<rules>
- Preserve meaning. Do not summarize, expand, or format as a list.
- Only correct words or short token groups that do not make sense in context.
- Allowed edits: substitution, merge, split, or the minimum tiny function-word insertion needed for the correction itself.
- Do not add newlines, bullets, paragraph breaks, or headings.
- Do not touch protected spans, identifiers, numbers, versions, dates, URLs, or emails.
- If the text already makes sense, leave it unchanged.
- Return JSON only: {"corrected_text":"","replacements":[{"from":"","to":"","reason":""}],"confidence":0.0}
</rules>
<field_context>
- multiline: ${fieldContext.isMultiline}
- input_type: ${fieldContext.inputType}
</field_context>
<protected_spans>
${ProtectedSpanDetector.toPromptLines(protectedSpans)}
</protected_spans>
<asr_hints>
$hintLines
</asr_hints>
<examples>
Input: "please send the deck to jane.doe@example.com"
Output: {"corrected_text":"please send the deck to jane.doe@example.com","replacements":[],"confidence":0.95}
Input: "we need to freeze the beach mark today"
Output: {"corrected_text":"we need to freeze the benchmark today","replacements":[{"from":"beach mark","to":"benchmark","reason":"contextual_asr_fix"}],"confidence":0.81}
</examples>
<input_text>
$cleanedText
</input_text>
            """.trimIndent()
        }

        internal fun buildListPlanningPrompt(
            correctedText: String,
            protectedSpans: List<ProtectedSpan>,
            fieldContext: FormatterFieldContext,
        ): String {
            return """
<task>
You are pass 3 of a speech formatter pipeline for a keyboard.
Decide only whether the text contains a list, where that list starts and ends, what the intro text is, what the list items are, and whether it is ordered or unordered.
</task>
<rules>
- Do not rewrite non-list prose.
- If uncertain, return no list plans.
- Keep non-list text untouched.
- Reject addresses, titles, dates, times, versions, decimals, URLs, emails, IDs, routing strings, and ordinary clause chains.
- Ordered lists require explicit numbering, ordinals, step/sequence meaning, or rank meaning.
- A count like "three things" without sequence meaning is unordered.
- Return JSON only:
  {"list_plans":[{"source_span_text":"","intro_text":"","items":[],"list_type":"UNORDERED","evidence_type":"INTRO_PLUS_ITEMS","ordered_reason":"","confidence":0.0}],"confidence":0.0}
</rules>
<field_context>
- multiline: ${fieldContext.isMultiline}
- input_type: ${fieldContext.inputType}
</field_context>
<protected_spans>
${ProtectedSpanDetector.toPromptLines(protectedSpans)}
</protected_spans>
<examples>
Input: "There are four things that matter. Name, place, animal, and thing."
Output: {"list_plans":[{"source_span_text":"There are four things that matter. Name, place, animal, and thing.","intro_text":"There are four things that matter","items":["Name","place","animal","thing"],"list_type":"UNORDERED","evidence_type":"CROSS_SENTENCE_INTRO","ordered_reason":"","confidence":0.88}],"confidence":0.88}
Input: "The build was green, the dashboard was red, and nobody trusted either one."
Output: {"list_plans":[],"confidence":0.93}
Input: "1. Freeze scope. 2. Rerun QA. 3. Ship the build."
Output: {"list_plans":[{"source_span_text":"1. Freeze scope. 2. Rerun QA. 3. Ship the build.","intro_text":"","items":["Freeze scope.","Rerun QA.","Ship the build."],"list_type":"ORDERED","evidence_type":"INLINE_NUMBERED_SEQUENCE","ordered_reason":"explicit_numbering","confidence":0.95}],"confidence":0.95}
</examples>
<input_text>
$correctedText
</input_text>
            """.trimIndent()
        }

        private const val PASS_CLEANUP = "cleanup"
        private const val PASS_CORRECTION = "correction"
        private const val PASS_LIST = "list"
    }
}
