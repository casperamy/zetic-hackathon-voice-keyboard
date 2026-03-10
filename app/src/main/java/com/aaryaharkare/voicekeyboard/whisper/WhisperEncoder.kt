package com.aaryaharkare.voicekeyboard.whisper

import android.content.Context
import android.util.Log
import com.zeticai.mlange.core.model.Target
import com.zeticai.mlange.core.model.ZeticMLangeModel
import com.zeticai.mlange.core.tensor.DataType
import com.zeticai.mlange.core.tensor.Tensor
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperEncoder(
    context: Context,
    personalKey: String,
    modelKey: String,
) {
    private val model = ZeticMLangeModel(context, personalKey, modelKey, target = Target.ORT)

    fun process(audioData: FloatArray): ByteBuffer {
        Log.d(TAG, "process: audioData.size=${audioData.size}")
        require(audioData.size == FEATURES_PER_SAMPLE) {
            "Unexpected mel feature size. expected=$FEATURES_PER_SAMPLE actual=${audioData.size}"
        }

        // This specific encoder model is compiled with fixed batch size 2.
        val batched = FloatArray(BATCH_SIZE * FEATURES_PER_SAMPLE)
        audioData.copyInto(batched, destinationOffset = 0)
        audioData.copyInto(batched, destinationOffset = FEATURES_PER_SAMPLE)

        val inputBuffer = ByteBuffer.allocateDirect(batched.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.asFloatBuffer().put(batched)
        inputBuffer.position(0)

        val input = Tensor(inputBuffer, DataType.Float32, intArrayOf(BATCH_SIZE, MEL_BINS, MEL_FRAMES))
        val outputs = model.run(arrayOf(input))
        Log.d(TAG, "process: encoder model returned ${outputs.size} outputs")
        return outputs[0].data.apply { position(0) }
    }

    fun close() {
        model.close()
    }

    companion object {
        private const val TAG = "VoiceKB"
        private const val BATCH_SIZE = 2
        private const val MEL_BINS = 80
        private const val MEL_FRAMES = 3000
        private const val FEATURES_PER_SAMPLE = MEL_BINS * MEL_FRAMES
    }
}
