package com.aaryaharkare.voicekeyboard.whisper

import android.content.Context
import android.util.Log
import com.zeticai.mlange.core.model.Target
import com.zeticai.mlange.core.model.ZeticMLangeModel
import com.zeticai.mlange.core.tensor.DataType
import com.zeticai.mlange.core.tensor.Tensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperDecoder(
    context: Context,
    personalKey: String,
    modelKey: String,
    private val startToken: Int = START_TOKEN,
    private val endToken: Int = END_TOKEN,
) {
    private val model = ZeticMLangeModel(context, personalKey, modelKey, target = Target.ORT)

    private val idsBuffer = ByteBuffer.allocateDirect(BATCH_SIZE * MAX_LENGTH * Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val attentionMaskBuffer = ByteBuffer.allocateDirect(BATCH_SIZE * MAX_LENGTH * Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val stepLogits = FloatArray(VOCAB_SIZE)

    suspend fun generateTokens(
        encoderOutput: ByteBuffer,
        maxLength: Int = MAX_LENGTH,
    ): List<Int> = withContext(Dispatchers.Default) {
        Log.d(TAG, "generateTokens: encoderOutput cap=${encoderOutput.capacity()} pos=${encoderOutput.position()} lim=${encoderOutput.limit()}")
        val decoderTokenIds = IntArray(maxLength) { PAD_TOKEN }
        decoderTokenIds[0] = startToken

        val decoderAttentionMask = IntArray(maxLength)
        decoderAttentionMask[0] = 1

        var idx = 1
        val generated = mutableListOf<Int>()

        while (idx < maxLength) {
            if (idx == 1) Log.d(TAG, "generateTokens: first decode step")
            val logits = decodeStep(decoderTokenIds, encoderOutput, decoderAttentionMask, idx - 1)
            if (idx == 1) Log.d(TAG, "generateTokens: first logits size=${logits.size}")

            val next = ProbabilityUtils.argmax(logits)

            if (next == endToken) break

            decoderTokenIds[idx] = next
            decoderAttentionMask[idx] = 1
            generated += next
            idx += 1
        }

        Log.d(TAG, "generateTokens: done, ${generated.size} tokens")
        generated
    }

    fun close() {
        model.close()
    }

    private fun decodeStep(
        idsSlice: IntArray,
        encoderOutput: ByteBuffer,
        decoderAttentionMask: IntArray,
        tokenIndex: Int,
    ): FloatArray {
        idsBuffer.clear()
        val idsLongView = idsBuffer.asLongBuffer()
        val maskLongView = attentionMaskBuffer.asLongBuffer()

        attentionMaskBuffer.clear()
        for (batch in 0 until BATCH_SIZE) {
            for (i in 0 until MAX_LENGTH) {
                idsLongView.put(idsSlice[i].toLong())
                maskLongView.put(decoderAttentionMask[i].toLong())
            }
        }

        idsBuffer.position(0)
        attentionMaskBuffer.position(0)

        val idsTensor = Tensor(idsBuffer, DataType.Int64, intArrayOf(BATCH_SIZE, MAX_LENGTH))
        val maskTensor = Tensor(attentionMaskBuffer, DataType.Int64, intArrayOf(BATCH_SIZE, MAX_LENGTH))

        encoderOutput.position(0)

        val outputs = model.run(
            arrayOf(
                idsTensor,
                Tensor.of(encoderOutput),
                maskTensor,
            ),
        )

        val outputFloat = outputs[0].data.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val offset = ((0 * MAX_LENGTH + tokenIndex) * VOCAB_SIZE)
        require(offset + VOCAB_SIZE <= outputFloat.capacity()) {
            "Decoder output too small for expected [2,448,$VOCAB_SIZE]. cap=${outputFloat.capacity()}"
        }
        outputFloat.position(offset)
        outputFloat.get(stepLogits, 0, VOCAB_SIZE)
        return stepLogits
    }

    companion object {
        private const val TAG = "VoiceKB"
        private const val PAD_TOKEN = 50256
        private const val START_TOKEN = 50258
        private const val END_TOKEN = 50257
        private const val MAX_LENGTH = 448
        private const val VOCAB_SIZE = 51865
        private const val BATCH_SIZE = 2
    }
}
