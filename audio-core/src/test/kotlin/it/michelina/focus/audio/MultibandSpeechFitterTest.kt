package it.michelina.focus.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MultibandSpeechFitterTest {
    @Test
    fun neutralSettingsReconstructInput() {
        val fitter = MultibandSpeechFitter(48_000)
        val input = FloatArray(1_024) { index ->
            (0.12 * sin(2.0 * PI * 930.0 * index / 48_000.0)).toFloat()
        }
        val output = FloatArray(input.size)

        val metrics = fitter.process(
            input,
            output,
            0f,
            0f,
            0f,
            FittingProfile.SPEECH,
            enabled = true,
        )

        val maximumError = input.indices.maxOf { abs(input[it] - output[it]) }
        assertTrue("Neutral seven-band reconstruction error $maximumError", maximumError < 1e-5f)
        assertTrue(abs(metrics.presenceDeltaDb) < 0.01f)
        assertTrue(abs(metrics.quietSpeechBoostDb) < 0.01f)
    }

    @Test
    fun presenceAndWeakSpeechProduceMeasuredImpact() {
        val fitter = MultibandSpeechFitter(16_000)
        val input = FloatArray(4_096) { index ->
            (0.015 * sin(2.0 * PI * 2_700.0 * index / 16_000.0)).toFloat()
        }
        val output = FloatArray(input.size)
        var metrics = SpeechFitMetrics(0f, 0f)
        repeat(8) {
            metrics = fitter.process(
                input,
                output,
                0.7f,
                8f,
                0.9f,
                FittingProfile.SPEECH,
                enabled = true,
            )
        }

        assertTrue("Presence impact ${metrics.presenceDeltaDb}", metrics.presenceDeltaDb > 1f)
        assertTrue("Weak-speech impact ${metrics.quietSpeechBoostDb}", metrics.quietSpeechBoostDb > 0.5f)
        assertTrue(output.all(Float::isFinite))
    }
}
