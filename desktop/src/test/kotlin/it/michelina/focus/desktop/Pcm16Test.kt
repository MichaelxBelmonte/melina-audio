package it.michelina.focus.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Pcm16Test {
    @Test
    fun littleEndianRoundTripPreservesEveryPcmExtreme() {
        val original = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE, 0x1234)
        val bytes = ByteArray(original.size * 2)
        val decoded = ShortArray(original.size)

        Pcm16.encodeLittleEndian(original, bytes)
        Pcm16.decodeLittleEndian(bytes, decoded)

        assertArrayEquals(original, decoded)
    }
}
