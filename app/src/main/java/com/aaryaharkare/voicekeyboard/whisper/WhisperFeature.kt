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

    suspend fun run(audio: FloatArray): String {
        require(audio.size >= MIN_AUDIO_SAMPLES) {
            "Speak a little longer before stopping"
        }

        Log.d(TAG, "run: raw audio ${audio.size} samples (${audio.size / 16000f}s)")

        // Whisper expects exactly 30 seconds of audio (480,000 samples at 16 kHz).
        // Pad short clips with silence or truncate long ones.
        val paddedAudio = when {
            audio.size == WHISPER_AUDIO_SAMPLES -> audio
            audio.size > WHISPER_AUDIO_SAMPLES -> audio.copyOfRange(0, WHISPER_AUDIO_SAMPLES)
            else -> FloatArray(WHISPER_AUDIO_SAMPLES).also { audio.copyInto(it) }
        }
        Log.d(TAG, "run: padded audio ${paddedAudio.size} samples")

        Log.d(TAG, "run: calling whisperWrapper.process()")
        val encodedFeatures = whisperWrapper.process(paddedAudio)
        Log.d(TAG, "run: mel features ${encodedFeatures.size} floats (expected 240000)")
        require(encodedFeatures.isNotEmpty()) {
            "Whisper feature extraction returned no data"
        }

        Log.d(TAG, "run: calling encoder.process()")
        val encoderOutputs = encoder.process(encodedFeatures)
        Log.d(TAG, "run: encoder done, output buffer capacity=${encoderOutputs.capacity()} pos=${encoderOutputs.position()} lim=${encoderOutputs.limit()}")
        encoderOutputs.position(0)

        Log.d(TAG, "run: calling decoder.generateTokens()")
        val generatedIds = decoder.generateTokens(encoderOutputs)
        Log.d(TAG, "run: decoder generated ${generatedIds.size} tokens")
        if (generatedIds.isEmpty()) {
            return ""
        }
        return whisperWrapper.decodeToken(generatedIds.toIntArray(), true).trim()
    }

    fun close() {
        encoder.close()
        decoder.close()
        whisperWrapper.deinit()
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

    companion object {
        private const val TAG = "VoiceKB"
        private const val MIN_AUDIO_SAMPLES = 4_000
        private const val WHISPER_AUDIO_SAMPLES = 480_000 // 30 seconds at 16 kHz
    }
}
