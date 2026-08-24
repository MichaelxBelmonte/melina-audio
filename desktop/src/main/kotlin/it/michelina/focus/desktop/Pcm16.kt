package it.michelina.focus.desktop

internal object Pcm16 {
    fun decodeLittleEndian(bytes: ByteArray, samples: ShortArray, sampleCount: Int = samples.size) {
        require(bytes.size >= sampleCount * 2)
        require(samples.size >= sampleCount)
        for (index in 0 until sampleCount) {
            val byteIndex = index * 2
            samples[index] = (
                (bytes[byteIndex].toInt() and 0xff) or
                    (bytes[byteIndex + 1].toInt() shl 8)
                ).toShort()
        }
    }

    fun encodeLittleEndian(samples: ShortArray, bytes: ByteArray, sampleCount: Int = samples.size) {
        require(samples.size >= sampleCount)
        require(bytes.size >= sampleCount * 2)
        for (index in 0 until sampleCount) {
            val value = samples[index].toInt()
            val byteIndex = index * 2
            bytes[byteIndex] = value.toByte()
            bytes[byteIndex + 1] = (value shr 8).toByte()
        }
    }
}
