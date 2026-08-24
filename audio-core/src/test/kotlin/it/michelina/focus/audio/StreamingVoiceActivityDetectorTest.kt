package it.michelina.focus.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingVoiceActivityDetectorTest {
    @Test
    fun everySupportedInputRateProducesOneSileroWindow() {
        val cases = listOf(8_000 to 256, 16_000 to 512, 48_000 to 1_536)
        for ((sampleRate, sampleCount) in cases) {
            val inference = FakeInference(0.9f)
            val detector = StreamingVoiceActivityDetector(sampleRate, inference = inference)
            val result = detector.process(ShortArray(sampleCount) { 1_000 })

            assertEquals("$sampleRate Hz window count", 1L, result.processedWindows)
            assertEquals("$sampleRate Hz inference calls", 1, inference.calls)
            assertTrue("$sampleRate Hz did not detect speech", result.speechDetected)
            detector.close()
            assertTrue(inference.closed)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedRateIsRejected() {
        StreamingVoiceActivityDetector(44_100, inference = FakeInference(0f))
    }

    private class FakeInference(private val probability: Float) : VoiceActivityInference {
        var calls = 0
        var closed = false

        override fun compute(window: FloatArray): Float {
            calls++
            return probability
        }

        override fun close() {
            closed = true
        }
    }
}
