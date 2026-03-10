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
                    val pcmBuffer = ShortArray(minBufferSize / 2)
                    val collected = FloatAccumulator(SAMPLE_RATE * 8)

                    try {
                        recorder.startRecording()
                        while (isRecording) {
                            val read = recorder.read(pcmBuffer, 0, pcmBuffer.size, AudioRecord.READ_BLOCKING)
                            if (read > 0) {
                                for (i in 0 until read) {
                                    val sample = (pcmBuffer[i] / Short.MAX_VALUE.toFloat()).coerceIn(-1f, 1f)
                                    collected.append(sample)
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
                        onAudioReady(collected.toArray())
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

    private class FloatAccumulator(initialCapacity: Int) {
        private var buffer = FloatArray(initialCapacity.coerceAtLeast(1))
        private var size = 0

        fun append(value: Float) {
            if (size == buffer.size) {
                buffer = buffer.copyOf(buffer.size * 2)
            }
            buffer[size] = value
            size += 1
        }

        fun toArray(): FloatArray = buffer.copyOf(size)
    }
}
