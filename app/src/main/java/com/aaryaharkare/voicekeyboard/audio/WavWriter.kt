package com.aaryaharkare.voicekeyboard.audio

import java.io.File
import java.io.FileOutputStream

object WavWriter {
    fun writeMono16BitPcm(
        file: File,
        samples: FloatArray,
        sampleRate: Int,
    ) {
        FileOutputStream(file).use { output ->
            val pcmData = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val sample =
                    (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE)
                        .toInt()
                        .toShort()
                pcmData[i * 2] = (sample.toInt() and 0xFF).toByte()
                pcmData[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }

            writeHeader(output, pcmData.size, sampleRate)
            output.write(pcmData)
        }
    }

    private fun writeHeader(
        out: FileOutputStream,
        totalAudioLen: Int,
        sampleRate: Int,
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * 2
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xFF).toByte()
        header[5] = ((totalDataLen shr 8) and 0xFF).toByte()
        header[6] = ((totalDataLen shr 16) and 0xFF).toByte()
        header[7] = ((totalDataLen shr 24) and 0xFF).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = 1
        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = ((sampleRate shr 8) and 0xFF).toByte()
        header[26] = ((sampleRate shr 16) and 0xFF).toByte()
        header[27] = ((sampleRate shr 24) and 0xFF).toByte()
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = ((byteRate shr 8) and 0xFF).toByte()
        header[30] = ((byteRate shr 16) and 0xFF).toByte()
        header[31] = ((byteRate shr 24) and 0xFF).toByte()
        header[32] = 2
        header[34] = 16
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xFF).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xFF).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xFF).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xFF).toByte()

        out.write(header, 0, header.size)
    }
}
