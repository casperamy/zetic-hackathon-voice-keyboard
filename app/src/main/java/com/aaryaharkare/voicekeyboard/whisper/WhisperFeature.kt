package com.aaryaharkare.voicekeyboard.whisper

import android.content.Context
import android.util.Log
import com.aaryaharkare.voicekeyboard.BuildConfig
import com.zeticai.mlange.feature.automaticspeechrecognition.whisper.WhisperWrapper
import java.io.File
import java.io.FileOutputStream

class WhisperFeature(
    private val context: Context,
    private val personalKey: String = BuildConfig.PERSONAL_KEY,
    private val encoderModelKey: String = BuildConfig.WHISPER_ENCODER_MODEL,
    private val decoderModelKey: String = BuildConfig.WHISPER_DECODER_MODEL,
) {
    private val encoder by lazy { WhisperEncoder(context, personalKey, encoderModelKey) }
    private val decoder by lazy { WhisperDecoder(context, personalKey, decoderModelKey) }
    private val whisperWrapper by lazy { WhisperWrapper(copyAssetToInternalStorage(context)) }

    private val paddedAudioBuffer = FloatArray(WHISPER_AUDIO_SAMPLES)

    fun ensureInitialized() {
        encoder
        decoder
        whisperWrapper
    }

    suspend fun run(audio: FloatArray): String = runWithMetrics(audio).transcript

    suspend fun runWithMetrics(audio: FloatArray): WhisperRunResult {
        require(audio.size >= MIN_AUDIO_SAMPLES) {
            "Speak a little longer before stopping"
        }

        val startedAt = System.nanoTime()
        val trimmedAudio = WhisperAudioPreprocessor.trimTrailingSilence(audio)
        val decodeCap = WhisperDecodePolicy.computeDecodeCap(trimmedAudio.size)
        val paddedAudio = toWhisperLength(trimmedAudio)

        val featureStartedAt = System.nanoTime()
        val encodedFeatures = whisperWrapper.process(paddedAudio)
        require(encodedFeatures.isNotEmpty()) {
            "Whisper feature extraction returned no data"
        }
        val featureFinishedAt = System.nanoTime()

        val encoderOutputs = encoder.process(encodedFeatures)
        val encoderFinishedAt = System.nanoTime()

        val decoderResult = decoder.generateTokens(encoderOutputs, maxDecodeTokens = decodeCap)

        val transcript = if (decoderResult.tokenCount == 0) {
            ""
        } else {
            val decoded =
                whisperWrapper.decodeToken(
                    decoderResult.tokenIds.copyOf(decoderResult.tokenCount),
                    true,
                )
            decoded?.trim().orEmpty()
        }

        val totalMs = nanosToMillis(System.nanoTime() - startedAt)
        val metrics =
            WhisperRunMetrics(
                trimmedAudioMs = samplesToMillis(trimmedAudio.size),
                audioReadyToFeatureExtractionMs = nanosToMillis(featureFinishedAt - startedAt),
                featureExtractionToEncoderMs = nanosToMillis(encoderFinishedAt - featureFinishedAt),
                decoderTotalMs = decoderResult.decoderMs,
                decoderSteps = decoderResult.decodeSteps,
                decoderCap = decoderResult.decodeCap,
                decoderStopReason = decoderResult.stopReason,
                totalPipelineMs = totalMs,
            )

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "perf trimmed=${metrics.trimmedAudioMs}ms " +
                    "audio->feature=${metrics.audioReadyToFeatureExtractionMs}ms " +
                    "feature->encoder=${metrics.featureExtractionToEncoderMs}ms " +
                    "decoder=${metrics.decoderTotalMs}ms " +
                    "steps=${metrics.decoderSteps}/${metrics.decoderCap} " +
                    "stop=${metrics.decoderStopReason.name.lowercase()} " +
                    "total=${metrics.totalPipelineMs}ms",
            )
        }

        return WhisperRunResult(transcript = transcript, metrics = metrics)
    }

    suspend fun warmup(): WhisperRunMetrics {
        return runWithMetrics(FloatArray(MIN_AUDIO_SAMPLES)).metrics
    }

    fun close() {
        encoder.close()
        decoder.close()
        whisperWrapper.deinit()
    }

    @Synchronized
    private fun toWhisperLength(audio: FloatArray): FloatArray {
        return when {
            audio.size == WHISPER_AUDIO_SAMPLES -> audio
            audio.size > WHISPER_AUDIO_SAMPLES -> audio.copyOfRange(0, WHISPER_AUDIO_SAMPLES)
            else -> {
                paddedAudioBuffer.fill(0f)
                audio.copyInto(paddedAudioBuffer)
                paddedAudioBuffer
            }
        }
    }

    private fun copyAssetToInternalStorage(
        context: Context,
        assetFileName: String = "vocab.json",
    ): String {
        val outFile = File(context.filesDir, assetFileName)
        if (outFile.exists() && outFile.length() > 0L) {
            return outFile.absolutePath
        }

        context.assets.open(assetFileName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile.absolutePath
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    private fun samplesToMillis(sampleCount: Int): Long =
        (sampleCount.toLong() * 1_000L) / AUDIO_SAMPLE_RATE

    data class WhisperRunResult(
        val transcript: String,
        val metrics: WhisperRunMetrics,
    )

    data class WhisperRunMetrics(
        val trimmedAudioMs: Long,
        val audioReadyToFeatureExtractionMs: Long,
        val featureExtractionToEncoderMs: Long,
        val decoderTotalMs: Long,
        val decoderSteps: Int,
        val decoderCap: Int,
        val decoderStopReason: WhisperDecodePolicy.StopReason,
        val totalPipelineMs: Long,
    )

    companion object {
        private const val TAG = "VoiceKB"
        private const val AUDIO_SAMPLE_RATE = 16_000
        const val MIN_AUDIO_SAMPLES = 4_000
        private const val WHISPER_AUDIO_SAMPLES = 480_000 // 30 seconds at 16 kHz
    }
}
