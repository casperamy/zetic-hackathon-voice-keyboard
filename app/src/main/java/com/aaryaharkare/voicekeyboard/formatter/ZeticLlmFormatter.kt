package com.aaryaharkare.voicekeyboard.formatter

import android.content.Context
import com.aaryaharkare.voicekeyboard.BuildConfig

class ZeticLlmFormatter(
    private val context: Context,
) : FormatterEngine {

    override suspend fun format(text: String): FormatterResult {
        val normalizedInput = text.trim()
        require(normalizedInput.isNotEmpty()) {
            "Formatter LLM received blank Whisper text"
        }

        val generation =
            FormatterLlmPipeline.generate(
                context = context,
                prompt = buildPrompt(normalizedInput),
            )
        val output = validateOutput(generation.output)
        return FormatterResult(
            formattedText = output,
            mode = FormatterMode.ZETIC_LLM,
            debugTrace =
                listOf(
                    "formatter_mode=ZETIC_LLM",
                    "model_key=${BuildConfig.FORMATTER_LLM_MODEL}",
                    "prompt_tokens=${generation.promptTokens}",
                    "generated_tokens=${generation.generatedTokens}",
                    "generation_ms=${generation.generationMs}",
                ),
            modelKey = BuildConfig.FORMATTER_LLM_MODEL,
            promptTokens = generation.promptTokens,
            generatedTokens = generation.generatedTokens,
            generationMs = generation.generationMs,
        )
    }

    companion object {
        private val systemPrompt =
            """
            You format raw Whisper transcription for a keyboard.
            Preserve the original wording and order.
            If the text clearly contains numbered list items, output an ordered list using `1.`, `2.`, etc.
            If the text clearly contains list items without numbering, output an unordered list using `• `.
            If the text is not clearly a list, return it unchanged.
            Do not add explanations, headings, quotes, markdown fences, or extra wording.
            Output only the final formatted text.
            """.trimIndent()

        internal fun buildPrompt(rawText: String): String {
            return buildString {
                append(systemPrompt)
                append("\n\nRaw text:\n")
                append(rawText.trim())
            }
        }

        internal fun validateOutput(output: String): String {
            val trimmed = output.trim()
            require(trimmed.isNotEmpty()) {
                "Formatter LLM returned blank output"
            }
            return trimmed
        }
    }
}
