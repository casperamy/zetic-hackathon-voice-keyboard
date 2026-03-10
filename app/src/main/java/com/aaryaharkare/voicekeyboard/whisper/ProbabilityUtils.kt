package com.aaryaharkare.voicekeyboard.whisper

object ProbabilityUtils {
    fun argmax(array: FloatArray): Int {
        require(array.isNotEmpty()) { "Decoder logits were empty" }
        var maxIndex = 0
        var maxValue = array[0]
        for (i in 1 until array.size) {
            if (array[i] > maxValue) {
                maxIndex = i
                maxValue = array[i]
            }
        }
        return maxIndex
    }
}
