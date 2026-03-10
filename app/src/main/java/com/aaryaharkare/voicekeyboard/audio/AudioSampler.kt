package com.aaryaharkare.voicekeyboard.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AudioSampler(
    private val onAudioReady: (FloatArray) -> Unit,
) {
    private val minBufferSize =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
        ).coerceAtLeast(SAMPLE_RATE / 2)

    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return

        val recorder =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize,
            )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        audioRecord = recorder
        isRecording = true

        recordThread =
            Thread(
                {
                    val temp = ShortArray(minBufferSize / 2)
                    val collected = ArrayList<Float>(SAMPLE_RATE * 10)

                    try {
                        recorder.startRecording()
                        while (isRecording) {
                            val read = recorder.read(temp, 0, temp.size, AudioRecord.READ_BLOCKING)
                            if (read > 0) {
                                for (i in 0 until read) {
                                    collected.add((temp[i] / Short.MAX_VALUE.toFloat()).coerceIn(-1f, 1f))
                                }
                            }
                        }
                    } finally {
                        try {
                            recorder.stop()
                        } catch (_: Exception) {
                        }
                        recorder.release()
                        if (audioRecord === recorder) {
                            audioRecord = null
                        }
                        onAudioReady(collected.toFloatArray())
                    }
                },
                "VoiceKeyboardAudioSampler",
            ).also { it.start() }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recordThread?.join(1500)
        recordThread = null
    }

    fun release() {
        stopRecording()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
