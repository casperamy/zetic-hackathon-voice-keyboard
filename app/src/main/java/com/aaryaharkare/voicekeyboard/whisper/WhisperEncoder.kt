package com.aaryaharkare.voicekeyboard.whisper

import android.content.Context
import com.zeticai.mlange.core.model.ModelMode
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
    private val model = ZeticMLangeModel(context, personalKey, modelKey, modelMode = ModelMode.RUN_AUTO)
    private val batchedInput = FloatArray(BATCH_SIZE * FEATURES_PER_SAMPLE)
    private val inputBuffer =
        ByteBuffer.allocateDirect(batchedInput.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val inputFloatBuffer = inputBuffer.asFloatBuffer()

    fun process(audioData: FloatArray): ByteBuffer {
        require(audioData.size == FEATURES_PER_SAMPLE) {
            "Unexpected mel feature size. expected=$FEATURES_PER_SAMPLE actual=${audioData.size}"
        }

        audioData.copyInto(batchedInput, destinationOffset = 0)
        audioData.copyInto(batchedInput, destinationOffset = FEATURES_PER_SAMPLE)

        inputFloatBuffer.position(0)
        inputFloatBuffer.put(batchedInput)
        inputBuffer.position(0)

        val input = Tensor(inputBuffer, DataType.Float32, intArrayOf(BATCH_SIZE, MEL_BINS, MEL_FRAMES))
        val outputs = model.run(arrayOf(input))
        return outputs[0].data.apply { position(0) }
    }

    fun close() {
        model.close()
    }

    companion object {
        private const val BATCH_SIZE = 2
        private const val MEL_BINS = 80
        private const val MEL_FRAMES = 3000
        private const val FEATURES_PER_SAMPLE = MEL_BINS * MEL_FRAMES
    }
}
