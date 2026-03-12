package com.aaryaharkare.voicekeyboard.formatter

import android.content.Context
import com.aaryaharkare.voicekeyboard.BuildConfig
import com.zeticai.mlange.core.model.ModelLoadingStatus
import com.zeticai.mlange.core.model.llm.LLMModelMode
import com.zeticai.mlange.core.model.llm.ZeticMLangeLLMModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

object FormatterLlmPipeline {
    private const val TAG = "VoiceKB"

    @Volatile
    private var model: ZeticMLangeLLMModel? = null

    @Volatile
    private var ready = false

    @Volatile
    private var loadingStatus = ModelLoadingStatus.UNKNOWN

    @Volatile
    private var loadingProgress = 0f

    @Volatile
    private var lastErrorMessage: String? = null

    fun snapshot(): FormatterLlmStatusSnapshot {
        return FormatterLlmStatusSnapshot(
            ready = ready,
            loadingStatus = loadingStatus,
            loadingProgress = loadingProgress,
            lastErrorMessage = lastErrorMessage,
        )
    }

    fun preload(context: Context) {
        get(context)
    }

    suspend fun preloadAndWarm(context: Context): FormatterLlmGenerationResult {
        return generate(
            context = context,
            prompt = ZeticLlmFormatter.buildPrompt(PRELOAD_SMOKE_INPUT),
        )
    }

    suspend fun generate(
        context: Context,
        prompt: String,
    ): FormatterLlmGenerationResult = withContext(Dispatchers.Default) {
        val startedAt = System.nanoTime()
        val llmModel = get(context)

        try {
            val runResult = llmModel.run(prompt)
            val output = StringBuilder()
            var generatedTokens = 0

            while (true) {
                val nextTokenResult = llmModel.waitForNextToken()
                val token = nextTokenResult.token
                generatedTokens = nextTokenResult.generatedTokens
                if (token.isEmpty()) {
                    break
                }
                output.append(token)
            }

            ready = true
            lastErrorMessage = null
            FormatterLlmGenerationResult(
                output = output.toString(),
                promptTokens = runResult.promptTokens,
                generatedTokens = generatedTokens,
                generationMs = nanosToMillis(System.nanoTime() - startedAt),
            )
        } catch (t: Throwable) {
            lastErrorMessage = t.message ?: "Formatter LLM inference failed"
            throw t
        } finally {
            runCatching { llmModel.cleanUp() }
        }
    }

    fun deinit() {
        synchronized(this) {
            runCatching { model?.deinit() }
            model = null
            ready = false
            loadingStatus = ModelLoadingStatus.UNKNOWN
            loadingProgress = 0f
        }
    }

    private fun get(context: Context): ZeticMLangeLLMModel {
        val existing = model
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            model ?: createModel(context.applicationContext).also { model = it }
        }
    }

    private fun createModel(context: Context): ZeticMLangeLLMModel {
        require(BuildConfig.PERSONAL_KEY.isNotBlank()) {
            "PERSONAL_KEY is missing. Add it to local.properties."
        }
        require(BuildConfig.FORMATTER_LLM_MODEL.isNotBlank()) {
            "FORMATTER_LLM_MODEL is missing. Add it to local.properties."
        }

        loadingStatus = ModelLoadingStatus.PENDING
        loadingProgress = 0f
        lastErrorMessage = null

        return try {
            ZeticMLangeLLMModel(
                context,
                BuildConfig.PERSONAL_KEY,
                BuildConfig.FORMATTER_LLM_MODEL,
                version = BuildConfig.FORMATTER_LLM_VERSION,
                modelMode = parseMode(BuildConfig.FORMATTER_LLM_MODE),
                onProgress = { progress ->
                    loadingProgress = progress.coerceIn(0f, 1f)
                },
                onStatusChanged = { status ->
                    loadingStatus = status
                    when (status) {
                        ModelLoadingStatus.COMPLETED -> {
                            ready = true
                            loadingProgress = max(loadingProgress, 1f)
                            lastErrorMessage = null
                        }
                        ModelLoadingStatus.FAILED,
                        ModelLoadingStatus.CANCELED,
                        ModelLoadingStatus.NOT_INSTALLED,
                        -> {
                            ready = false
                        }
                        else -> Unit
                    }
                },
            ).also {
                loadingProgress = max(loadingProgress, 1f)
                loadingStatus = ModelLoadingStatus.COMPLETED
                ready = true
            }
        } catch (t: Throwable) {
            ready = false
            loadingProgress = 0f
            loadingStatus = ModelLoadingStatus.FAILED
            lastErrorMessage = t.message ?: "Formatter LLM download failed"
            throw t
        }
    }

    private fun parseMode(value: String): LLMModelMode {
        return runCatching { LLMModelMode.valueOf(value) }
            .getOrDefault(LLMModelMode.RUN_AUTO)
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    data class FormatterLlmStatusSnapshot(
        val ready: Boolean,
        val loadingStatus: ModelLoadingStatus,
        val loadingProgress: Float,
        val lastErrorMessage: String?,
    )

    data class FormatterLlmGenerationResult(
        val output: String,
        val promptTokens: Int,
        val generatedTokens: Int,
        val generationMs: Long,
    )

    private const val PRELOAD_SMOKE_INPUT = "Buy milk eggs and bread"
}
