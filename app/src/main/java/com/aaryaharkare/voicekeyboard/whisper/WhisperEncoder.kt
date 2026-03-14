package com.aaryaharkare.voicekeyboard.whisper

import android.content.Context
import android.util.Log
import com.zeticai.mlange.core.model.ModelMode
import com.zeticai.mlange.core.model.TargetModel
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
    private var backendLogged = false
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
        logBackendOnce()
        val outputs = model.run(arrayOf(input))
        return outputs[0].data.apply { position(0) }
    }

    fun close() {
        model.close()
    }

    private fun logBackendOnce() {
        if (backendLogged) return
        synchronized(this) {
            if (backendLogged) return
            backendLogged = true
        }

        try {
            val field = model.javaClass.getDeclaredField("targetModel")
            field.isAccessible = true
            val targetModel = field.get(model) as? TargetModel
            val apType = targetModel?.apType
            val target = targetModel?.target
            Log.d(TAG, "whisper_backend encoder apType=$apType target=$target")
        } catch (t: Throwable) {
            Log.w(TAG, "whisper_backend encoder unknown: ${t.message}", t)
        }
    }

    companion object {
        private const val TAG = "VoiceKB"
        private const val BATCH_SIZE = 2
        private const val MEL_BINS = 80
        private const val MEL_FRAMES = 3000
        private const val FEATURES_PER_SAMPLE = MEL_BINS * MEL_FRAMES
    }
}
