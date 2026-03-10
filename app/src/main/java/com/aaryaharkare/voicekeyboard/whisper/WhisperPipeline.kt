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
        return metrics
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
}
