package com.aaryaharkare.voicekeyboard.whisper

import android.content.Context
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

    private val idsBuffer =
        ByteBuffer.allocateDirect(BATCH_SIZE * MODEL_SEQUENCE_LENGTH * Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
    private val attentionMaskBuffer =
        ByteBuffer.allocateDirect(BATCH_SIZE * MODEL_SEQUENCE_LENGTH * Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

    private val idsTensor = Tensor(idsBuffer, DataType.Int64, intArrayOf(BATCH_SIZE, MODEL_SEQUENCE_LENGTH))
    private val maskTensor = Tensor(attentionMaskBuffer, DataType.Int64, intArrayOf(BATCH_SIZE, MODEL_SEQUENCE_LENGTH))
    private val stepLogits = FloatArray(VOCAB_SIZE)

    suspend fun generateTokens(
        encoderOutput: ByteBuffer,
        maxDecodeTokens: Int = DEFAULT_MAX_DECODE_TOKENS,
    ): DecoderRunResult = withContext(Dispatchers.Default) {
        val decodeCap = maxDecodeTokens.coerceIn(1, MODEL_SEQUENCE_LENGTH - 1)
        val startedAt = System.nanoTime()

        initializeDecoderInputs()
        setDecoderToken(position = 0, value = startToken)
        setDecoderMask(position = 0, value = 1)

        encoderOutput.position(0)
        val encoderTensor = Tensor.of(encoderOutput)
        val modelInputs = arrayOf(idsTensor, encoderTensor, maskTensor)

        val generated = IntArray(decodeCap)
        var generatedCount = 0
        var decodeSteps = 0
        var tokenPosition = 1

        while (generatedCount < decodeCap && tokenPosition < MODEL_SEQUENCE_LENGTH) {
            val logits = decodeStep(modelInputs, tokenPosition - 1)
            decodeSteps += 1

            val nextToken = ProbabilityUtils.argmax(logits)
            if (nextToken == endToken) {
                break
            }
            if (shouldStopForRepetition(generated, generatedCount, nextToken)) {
                break
            }

            generated[generatedCount] = nextToken
            generatedCount += 1

            setDecoderToken(position = tokenPosition, value = nextToken)
            setDecoderMask(position = tokenPosition, value = 1)
            tokenPosition += 1
        }

        DecoderRunResult(
            tokenIds = generated,
            tokenCount = generatedCount,
            decodeSteps = decodeSteps,
            decoderMs = nanosToMillis(System.nanoTime() - startedAt),
        )
    }

    fun close() {
        model.close()
    }

    private fun decodeStep(
        modelInputs: Array<Tensor>,
        tokenIndex: Int,
    ): FloatArray {
        val outputs = model.run(modelInputs)
        val outputFloat = outputs[0].data.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

        val offset = tokenIndex * VOCAB_SIZE
        require(offset + VOCAB_SIZE <= outputFloat.capacity()) {
            "Decoder output too small for expected logits. cap=${outputFloat.capacity()}"
        }

        outputFloat.position(offset)
        outputFloat.get(stepLogits, 0, VOCAB_SIZE)
        return stepLogits
    }

    private fun initializeDecoderInputs() {
        for (batch in 0 until BATCH_SIZE) {
            val batchOffset = batch * MODEL_SEQUENCE_LENGTH * Long.SIZE_BYTES
            for (pos in 0 until MODEL_SEQUENCE_LENGTH) {
                val byteOffset = batchOffset + pos * Long.SIZE_BYTES
                idsBuffer.putLong(byteOffset, PAD_TOKEN.toLong())
                attentionMaskBuffer.putLong(byteOffset, 0L)
            }
        }
        idsBuffer.position(0)
        attentionMaskBuffer.position(0)
    }

    private fun setDecoderToken(position: Int, value: Int) {
        for (batch in 0 until BATCH_SIZE) {
            val offset = ((batch * MODEL_SEQUENCE_LENGTH) + position) * Long.SIZE_BYTES
            idsBuffer.putLong(offset, value.toLong())
        }
    }

    private fun setDecoderMask(position: Int, value: Int) {
        for (batch in 0 until BATCH_SIZE) {
            val offset = ((batch * MODEL_SEQUENCE_LENGTH) + position) * Long.SIZE_BYTES
            attentionMaskBuffer.putLong(offset, value.toLong())
        }
    }

    private fun shouldStopForRepetition(
        generated: IntArray,
        generatedCount: Int,
        nextToken: Int,
    ): Boolean {
        if (generatedCount >= 2) {
            val prev = generated[generatedCount - 1]
            val prev2 = generated[generatedCount - 2]
            if (nextToken == prev && prev == prev2) {
                return true
            }
        }

        if (generatedCount >= 5) {
            val a = generated[generatedCount - 1]
            val b = generated[generatedCount - 2]
            val aPrev = generated[generatedCount - 3]
            val bPrev = generated[generatedCount - 4]
            val aPrev2 = generated[generatedCount - 5]
            if (nextToken == a && a == aPrev && a == aPrev2 && b == bPrev) {
                return true
            }
        }

        return false
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    data class DecoderRunResult(
        val tokenIds: IntArray,
        val tokenCount: Int,
        val decodeSteps: Int,
        val decoderMs: Long,
    )

    companion object {
        private const val PAD_TOKEN = 50256
        private const val START_TOKEN = 50258
        private const val END_TOKEN = 50257
        private const val MODEL_SEQUENCE_LENGTH = 448
        private const val DEFAULT_MAX_DECODE_TOKENS = 128
        private const val VOCAB_SIZE = 51865
        private const val BATCH_SIZE = 2
    }
}
