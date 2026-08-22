package it.michelina.focus.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeechOutputDynamicsTest {
    @Test
    fun quietSpeechRemainsTransparentAtZeroGain() {
        val dynamics = SpeechOutputDynamics(48_000)
        var output = 0f
        repeat(48_000) { output = dynamics.process(0.03f, 0f) }

        assertTrue("Quiet speech changed to $output", abs(output - 0.03f) < 0.0001f)
    }

    @Test
    fun requestedGainRaisesQuietSpeech() {
        val dynamics = SpeechOutputDynamics(48_000)
        var output = 0f
        repeat(2_000) { output = dynamics.process(0.01f, 6f) }

        assertTrue("6 dB gain produced $output", output in 0.0195f..0.0205f)
    }

    @Test
    fun loudTransientIsBoundedFromFirstSample() {
        val dynamics = SpeechOutputDynamics(48_000)
        val output = dynamics.process(1f, 12f)

        assertTrue("Transient escaped limiter: $output", abs(output) <= SpeechOutputDynamics.LIMITER_CEILING)
    }

    @Test
    fun stateCanBeResetBetweenExperiments() {
        val dynamics = SpeechOutputDynamics(48_000)
        repeat(48_000) { dynamics.process(0.8f, 12f) }
        dynamics.reset()
        val output = dynamics.process(0.01f, 0f)

        assertTrue("Reset retained previous attenuation: $output", output > 0.0099f)
    }
}
