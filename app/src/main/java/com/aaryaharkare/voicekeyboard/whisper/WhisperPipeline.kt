package com.aaryaharkare.voicekeyboard.whisper

import android.content.Context
import android.util.Log
import com.aaryaharkare.voicekeyboard.BuildConfig

object WhisperPipeline {
    private const val TAG = "VoiceKB"

    @Volatile
    private var pipeline: WhisperFeature? = null

    @Volatile
    private var warmupDone = false

    @Volatile
    private var loading = false

    @Volatile
    private var lastErrorMessage: String? = null

    fun snapshot(): WhisperStatusSnapshot {
        return WhisperStatusSnapshot(
            warmupDone = warmupDone,
            loading = loading,
            lastErrorMessage = lastErrorMessage,
        )
    }

    fun get(context: Context): WhisperFeature {
        val existing = pipeline
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val created =
                pipeline
                    ?: WhisperFeature(
                        context = context.applicationContext,
                        personalKey = BuildConfig.PERSONAL_KEY,
                        encoderModelKey = BuildConfig.WHISPER_ENCODER_MODEL,
                        decoderModelKey = BuildConfig.WHISPER_DECODER_MODEL,
                    ).also { pipeline = it }
            created
        }
    }

    fun preload(context: Context) {
        get(context).ensureInitialized()
    }

    suspend fun preloadAndWarm(context: Context): WhisperFeature.WhisperRunMetrics {
        loading = true
        lastErrorMessage = null

        return try {
            val whisper = get(context)
            whisper.ensureInitialized()
            val metrics = whisper.warmup()
            warmupDone = true
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "warmup done total=${metrics.totalPipelineMs}ms decoder=${metrics.decoderTotalMs}ms",
                )
            }
            metrics
        } catch (t: Throwable) {
            warmupDone = false
            lastErrorMessage = t.message ?: "Whisper load failed"
            throw t
        } finally {
            loading = false
        }
    }

    suspend fun warmupBestEffort(context: Context): WhisperFeature.WhisperRunMetrics? {
        return try {
            preloadAndWarm(context)
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Warmup skipped: ${t.message}", t)
            }
            null
        }
    }

    fun isWarmupDone(): Boolean = warmupDone

    data class WhisperStatusSnapshot(
        val warmupDone: Boolean,
        val loading: Boolean,
        val lastErrorMessage: String?,
    )
}
