package it.michelina.focus.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AdaptiveSpeechLevelerTest {
    @Test
    fun weakSpeechReceivesRequestedBoost() {
        val leveler = AdaptiveSpeechLeveler(SAMPLE_RATE)
        var output = 0f
        repeat(SAMPLE_RATE) {
            output = leveler.process(
                input = 0.005f,
                speechProbability = 1f,
                requestedBoostDb = 12f,
                enabled = true,
            )
        }

        assertTrue("Applied ${leveler.currentBoostDb} dB", leveler.currentBoostDb > 10f)
        assertTrue("Weak speech was not raised enough", output > 0.015f)
    }

    @Test
    fun speechGateDoesNotRaiseWeakRoomNoise() {
        val leveler = AdaptiveSpeechLeveler(SAMPLE_RATE)
        var maximumOutput = 0f
        repeat(SAMPLE_RATE) {
            val output = leveler.process(
                input = 0.005f,
                speechProbability = 0.1f,
                requestedBoostDb = 12f,
                enabled = true,
            )
            maximumOutput = maxOf(maximumOutput, abs(output))
        }

        assertTrue("Noise boost was ${leveler.currentBoostDb} dB", leveler.currentBoostDb < 0.05f)
        assertTrue("Noise output changed to $maximumOutput", maximumOutput < 0.0051f)
    }

    @Test
    fun alreadyLoudSpeechIsNotRaised() {
        val leveler = AdaptiveSpeechLeveler(SAMPLE_RATE)
        repeat(SAMPLE_RATE) {
            leveler.process(
                input = 0.20f,
                speechProbability = 1f,
                requestedBoostDb = 12f,
                enabled = true,
            )
        }

        assertTrue("Loud speech boost was ${leveler.currentBoostDb} dB", leveler.currentBoostDb < 0.05f)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
    }
}
