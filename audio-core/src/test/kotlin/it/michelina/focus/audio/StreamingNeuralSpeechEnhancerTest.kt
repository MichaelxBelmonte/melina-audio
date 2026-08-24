package it.michelina.focus.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StreamingNeuralSpeechEnhancerTest {
    @Test
    fun bypassUsesTheSharedAlignedDryPathAndClosesAdapters() {
        val denoiser = FakeDenoiser()
        val detector = FakeDetector()
        val processor = StreamingNeuralSpeechEnhancer(
            ProcessorBackend.GTCRN_FAST,
            denoiser,
            detector,
        )
        val first = ShortArray(256) { index -> (index * 17 - 2_000).toShort() }
        val second = ShortArray(256) { 500 }
        val output = ShortArray(256)
        val settings = ProcessorSettings(mode = ProcessingMode.BYPASS, gainDb = 0f)

        processor.process(first, output, settings)
        assertTrue("First aligned frame should be silence", output.all { it == 0.toShort() })

        processor.process(second, output, settings)
        val maximumError = output.indices.maxOf { abs(output[it].toInt() - first[it].toInt()) }
        assertTrue("Aligned dry frame error was $maximumError", maximumError <= 2)

        processor.close()
        assertTrue(denoiser.closed)
        assertTrue(detector.closed)
    }

    private class FakeDenoiser : StreamingSpeechDenoiser {
        override val sampleRate = 16_000
        override val frameShiftSamples = 256
        var closed = false

        override fun run(input: FloatArray): FloatArray = input.copyOf()

        override fun close() {
            closed = true
        }
    }

    private class FakeDetector : VoiceActivityDetector {
        var closed = false

        override fun process(input: ShortArray) = VoiceActivityEstimate(
            probability = 0.8f,
            rawProbability = 0.8f,
            speechDetected = true,
            processedWindows = 1,
            averageInferenceMs = 0.1f,
            modelName = "FAKE VAD",
        )

        override fun close() {
            closed = true
        }
    }
}
