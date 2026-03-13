package com.aaryaharkare.voicekeyboard.formatter

import android.content.Context

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
                "formatter_mode=LIST_ONLY_LLM",
                "model_key=${FormatterLlmPipeline.modelKey()}",
                "protected_spans=${protectedSpans.size}",
                "field_multiline=${fieldContext.isMultiline}",
                "field_input_type=${fieldContext.inputType}",
            )

        val listGeneration =
            FormatterLlmPipeline.generate(
                context = context,
                prompt = buildListPlanningPrompt(rawText, protectedSpans, fieldContext),
            )
        val listResolution =
            FormatterPassValidator.resolveListPlans(
                text = rawText,
                payload = FormatterJsonParser.parseListPlanning(listGeneration.output),
                protectedSpans = protectedSpans,
            )
        val acceptedPlans = listResolution.acceptedPlans

        val finalText =
            if (acceptedPlans.isEmpty()) {
                rawText
            } else {
                ListPlanRenderer.render(rawText, acceptedPlans)
            }

        passMetrics +=
            FormatterPassMetrics(
                name = PASS_LIST,
                accepted = acceptedPlans.isNotEmpty(),
                reason = listResolution.reason,
                confidence = listResolution.confidence,
                promptTokens = listGeneration.promptTokens,
                generatedTokens = listGeneration.generatedTokens,
                generationMs = listGeneration.generationMs,
            )
        debugTrace += "pass=$PASS_LIST raw_output=${listGeneration.output.ifBlank { "<empty>" }}"
        debugTrace +=
            "pass=$PASS_LIST accepted=${acceptedPlans.size} reason=${listResolution.reason} " +
                "confidence=${listResolution.confidence?.formatConfidence() ?: "-"} " +
                "prompt_tokens=${listGeneration.promptTokens} generated_tokens=${listGeneration.generatedTokens} " +
                "generation_ms=${listGeneration.generationMs}"
        acceptedPlans.forEachIndexed { index, plan ->
            debugTrace +=
                "pass=$PASS_LIST plan=${index + 1} type=${plan.listType.name.lowercase()} " +
                    "evidence=${plan.evidenceType.name.lowercase()} confidence=${plan.confidence.formatConfidence()} " +
                    "items=${plan.items.joinToString(prefix = "[", postfix = "]")}"
        }

        return FormatterResult(
            formattedText = finalText,
            mode = FormatterMode.LIST_ONLY_LLM,
            debugTrace = debugTrace,
            modelKey = FormatterLlmPipeline.modelKey(),
            promptTokens = listGeneration.promptTokens,
            generatedTokens = listGeneration.generatedTokens,
            generationMs = listGeneration.generationMs,
            rawText = rawText,
            rawLlmOutput = listGeneration.output,
            listPlans = acceptedPlans,
            passMetrics = passMetrics,
            fallbackReason = listResolution.reason.takeIf { acceptedPlans.isEmpty() },
        )
    }

    private fun Double.formatConfidence(): String = String.format(java.util.Locale.US, "%.2f", this)

    companion object {
        internal fun buildWarmupPrompt(rawText: String): String {
            return buildListPlanningPrompt(
                correctedText = rawText,
                protectedSpans = emptyList(),
                fieldContext = FormatterFieldContext(inputType = 0, isMultiline = true),
            )
        }

        internal fun buildListPlanningPrompt(
            correctedText: String,
            protectedSpans: List<ProtectedSpan>,
            fieldContext: FormatterFieldContext,
        ): String {
            return """
<task>
You are the only formatting pass in a speech formatter pipeline for a keyboard.
Decide whether the text contains a list and return a structured list plan.
</task>
<rules>
- Always return exactly one JSON object and nothing else.
- Do not wrap the JSON in markdown or code fences.
- If the text is not a list, return {"list_plans":[],"confidence":0.0}.
- If you are unsure, return {"list_plans":[],"confidence":0.0}.
- Each object inside "list_plans" must include only these fields:
  "source_span_text", "intro_text", "items", "list_type", "confidence"
- "items" must contain at least 2 strings.
- "list_type" must be exactly "ORDERED" or "UNORDERED".
- "source_span_text" must be an exact substring copied from the input text.
- If you cannot fill every required field for a plan, return {"list_plans":[],"confidence":0.0} instead.
- Keep non-list text untouched.
- Reject addresses, titles, dates, times, versions, decimals, URLs, emails, IDs, routing strings, and ordinary clause chains.
- Ordered lists require explicit numbering, ordinals, step/sequence meaning, or rank meaning.
- A count like "three things" without sequence meaning is unordered.
- Return JSON only:
  {"list_plans":[{"source_span_text":"","intro_text":"","items":[],"list_type":"UNORDERED","confidence":0.0}],"confidence":0.0}
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
Output: {"list_plans":[{"source_span_text":"There are four things that matter. Name, place, animal, and thing.","intro_text":"There are four things that matter","items":["Name","place","animal","thing"],"list_type":"UNORDERED","confidence":0.88}],"confidence":0.88}
Input: "The most important things are name, place, animal, thing, and feelings."
Output: {"list_plans":[{"source_span_text":"The most important things are name, place, animal, thing, and feelings.","intro_text":"The most important things are","items":["name","place","animal","thing","feelings"],"list_type":"UNORDERED","confidence":0.86}],"confidence":0.86}
Input: "The build was green, the dashboard was red, and nobody trusted either one."
Output: {"list_plans":[],"confidence":0.93}
Input: "1. Freeze scope. 2. Rerun QA. 3. Ship the build."
Output: {"list_plans":[{"source_span_text":"1. Freeze scope. 2. Rerun QA. 3. Ship the build.","intro_text":"","items":["Freeze scope.","Rerun QA.","Ship the build."],"list_type":"ORDERED","confidence":0.95}],"confidence":0.95}
Input: "{\"list_plans\":[{\"source_span_text\":\"text only\",\"intro_text\":\"text only\"}],\"confidence\":0.0}"
Output: {"list_plans":[],"confidence":0.0}
</examples>
<input_text>
$correctedText
</input_text>
            """.trimIndent()
        }

        private const val PASS_LIST = "list"
    }
}
