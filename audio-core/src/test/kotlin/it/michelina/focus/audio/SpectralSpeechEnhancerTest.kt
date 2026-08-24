package it.michelina.focus.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SpectralSpeechEnhancerTest {
    @Test
    fun bypassPreservesLowLevelSignal() {
        val enhancer = SpectralSpeechEnhancer()
        val input = ShortArray(SpectralSpeechEnhancer.HOP_SIZE) { index ->
            ((index % 31) - 15).times(120).toShort()
        }
        val output = ShortArray(input.size)

        enhancer.process(
            input,
            output,
            ProcessorSettings(mode = ProcessingMode.BYPASS, gainDb = 0f),
        )

        val maximumError = input.indices.maxOf { index ->
            abs(input[index].toInt() - output[index].toInt())
        }
        assertTrue("Bypass error was $maximumError PCM units", maximumError <= 2)
    }

    @Test
    fun limiterNeverExceedsDigitalCeiling() {
        val enhancer = SpectralSpeechEnhancer()
        val input = ShortArray(SpectralSpeechEnhancer.HOP_SIZE) { Short.MAX_VALUE }
        val output = ShortArray(input.size)

        enhancer.process(
            input,
            output,
            ProcessorSettings(mode = ProcessingMode.BYPASS, gainDb = 12f),
        )

        val peak = output.maxOf { abs(it.toInt()) }
        assertTrue("Limiter peak was $peak", peak <= (32_767 * 0.921f).toInt())
    }

    @Test
    fun telemetryMeasuresEffectiveGainOnQuietSignal() {
        val enhancer = SpectralSpeechEnhancer()
        val input = ShortArray(SpectralSpeechEnhancer.HOP_SIZE) { index ->
            if (index % 2 == 0) 1_000 else -1_000
        }
        val output = ShortArray(input.size)

        val metrics = enhancer.process(
            input,
            output,
            ProcessorSettings(mode = ProcessingMode.BYPASS, gainDb = 6f),
        )

        assertTrue("Gain telemetry was ${metrics.effectiveGainDb} dB", metrics.effectiveGainDb in 5.8f..6.2f)
        assertTrue("Processor frame counter did not advance", metrics.processedFrames == 1L)
        assertTrue("Bypass must report no denoise change", metrics.signalChangedPercent == 0f)
    }

    @Test
    fun hfpEightKilohertzPathRemainsStableAndBounded() {
        val enhancer = SpectralSpeechEnhancer(sampleRate = 8_000)
        val input = ShortArray(SpectralSpeechEnhancer.HOP_SIZE)
        val output = ShortArray(input.size)
        val settings = ProcessorSettings(
            mode = ProcessingMode.VOICE_FOCUS,
            gainDb = 9f,
            denoiseStrength = 0.7f,
            clarity = 0.6f,
            useSystemNoiseSuppressor = false,
        )

        repeat(30) { frame ->
            for (i in input.indices) {
                val time = (frame * input.size + i).toDouble() / 8_000.0
                input[i] = (9_000.0 * sin(2.0 * PI * 900.0 * time)).toInt().toShort()
            }
            enhancer.process(input, output, settings)
        }

        val peak = output.maxOf { abs(it.toInt()) }
        assertTrue("HFP output should remain audible", peak > 1_000)
        assertTrue("HFP limiter peak was $peak", peak <= (32_767 * 0.921f).toInt())
    }

    @Test
    fun voiceFocusAttenuatesStationaryNoiseAndKeepsSpeechAudible() {
        val enhancer = SpectralSpeechEnhancer()
        val random = Random(7)
        val input = ShortArray(SpectralSpeechEnhancer.HOP_SIZE)
        val output = ShortArray(input.size)
        val settings = ProcessorSettings(
            mode = ProcessingMode.VOICE_FOCUS,
            gainDb = 0f,
            denoiseStrength = 1f,
            clarity = 0f,
            useSystemNoiseSuppressor = false,
        )

        var lastNoiseInputRms = 0.0
        var lastNoiseOutputRms = 0.0
        repeat(90) {
            for (i in input.indices) input[i] = (random.nextGaussian() * 1_100).toInt().toShort()
            enhancer.process(input, output, settings)
            lastNoiseInputRms = rms(input)
            lastNoiseOutputRms = rms(output)
        }

        var speechOutputRms = 0.0
        repeat(12) { frame ->
            for (i in input.indices) {
                val time = (frame * input.size + i).toDouble() / SpectralSpeechEnhancer.SAMPLE_RATE
                val voiceLike = 4_500.0 * sin(2.0 * PI * 780.0 * time)
                val noise = random.nextGaussian() * 700.0
                input[i] = (voiceLike + noise).toInt().coerceIn(-32_768, 32_767).toShort()
            }
            val metrics = enhancer.process(input, output, settings)
            assertTrue(metrics.denoiseDeltaDb.isFinite())
            assertTrue(metrics.signalChangedPercent.isFinite())
            assertTrue(metrics.presenceDeltaDb.isFinite())
            assertTrue(metrics.effectiveGainDb.isFinite())
            speechOutputRms = rms(output)
        }

        assertTrue(
            "Noise was not attenuated: in=$lastNoiseInputRms out=$lastNoiseOutputRms",
            lastNoiseOutputRms < lastNoiseInputRms * 0.85,
        )
        assertTrue(
            "Speech should remain clearly above residual noise",
            speechOutputRms > lastNoiseOutputRms * 1.8,
        )
    }

    private fun rms(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / samples.size)
    }
}
