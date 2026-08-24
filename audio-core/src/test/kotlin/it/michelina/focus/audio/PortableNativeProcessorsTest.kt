package it.michelina.focus.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PortableNativeProcessorsTest {
    @Test
    fun ulunasFrontendRunsWithAPlatformInferenceAdapter() {
        val inference = FakeUlunasInference()
        val processor = UlunasAudioProcessor(inference, FixedVoiceDetector())
        val input = ShortArray(UlunasAudioProcessor.HOP_SIZE)
        val output = ShortArray(input.size)
        var metrics = processor.process(input, output, ProcessorSettings())
        repeat(4) { frame ->
            fillTone(input, frame, UlunasAudioProcessor.SAMPLE_RATE)
            metrics = processor.process(input, output, ProcessorSettings())
        }

        assertEquals(5, inference.calls)
        assertTrue(metrics.inputDbFs.isFinite())
        assertTrue(metrics.outputDbFs.isFinite())
        assertTrue(output.any { it != 0.toShort() })
        processor.close()
        assertTrue(inference.closed)
    }

    @Test
    fun deepFilterFrontendAlignsDryAudioAndOwnsItsAdapter() {
        val inference = FakeDeepFilterInference()
        val processor = DeepFilterAudioProcessor(inference, FixedVoiceDetector())
        val input = ShortArray(DeepFilterAudioProcessor.FRAME_SIZE)
        val output = ShortArray(input.size)
        val settings = ProcessorSettings(mode = ProcessingMode.BYPASS)
        repeat(6) { frame ->
            fillTone(input, frame, DeepFilterAudioProcessor.SAMPLE_RATE)
            processor.process(input, output, settings)
        }

        assertEquals(
            DeepFilterAudioProcessor.DEFAULT_ATTENUATION_LIMIT_DB,
            inference.attenuationDb,
            0f,
        )
        assertTrue(output.any { it != 0.toShort() })
        processor.close()
        assertTrue(inference.closed)
    }

    private fun fillTone(samples: ShortArray, frame: Int, sampleRate: Int) {
        for (index in samples.indices) {
            val position = frame * samples.size + index
            samples[index] = (4_000.0 * sin(2.0 * PI * 700.0 * position / sampleRate))
                .toInt()
                .toShort()
        }
    }

    private class FakeUlunasInference : UlunasInference {
        var calls = 0
        var closed = false

        override fun process(
            inputSpectrum: FloatArray,
            convCache: FloatArray,
            tfaCache: FloatArray,
            interCache: FloatArray,
            enhancedSpectrum: FloatArray,
        ) {
            inputSpectrum.copyInto(enhancedSpectrum)
            convCache[0] += 1f
            tfaCache[0] += 1f
            interCache[0] += 1f
            calls++
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeDeepFilterInference : DeepFilterInference {
        override val frameSizeSamples = DeepFilterAudioProcessor.FRAME_SIZE
        var attenuationDb = 0f
        var closed = false

        override fun process(input: ShortArray, output: FloatArray): Float {
            for (index in output.indices) output[index] = input[index] / 32768f
            return 0f
        }

        override fun setParameters(attenuationLimitDb: Float, postFilterBeta: Float) {
            attenuationDb = attenuationLimitDb
        }

        override fun close() {
            closed = true
        }
    }

    private class FixedVoiceDetector : VoiceActivityDetector {
        override fun process(input: ShortArray) = VoiceActivityEstimate(
            probability = 0.75f,
            rawProbability = 0.75f,
            speechDetected = true,
            processedWindows = 1,
            averageInferenceMs = 0f,
            modelName = "TEST VAD",
        )
    }
}
