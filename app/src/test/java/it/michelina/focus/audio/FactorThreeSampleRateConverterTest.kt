package it.michelina.focus.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class FactorThreeSampleRateConverterTest {
    @Test
    fun speechBandToneSurvivesRoundTrip() {
        val converter = FactorThreeSampleRateConverter(MODEL_FRAME)
        val input = ShortArray(NATIVE_FRAME)
        val model = ShortArray(MODEL_FRAME)
        val output = ShortArray(NATIVE_FRAME)
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        var samplesMeasured = 0

        repeat(80) { frame ->
            fillTone(input, frame, frequencyHz = 1_000.0, amplitude = 8_000.0)
            converter.downsample(input, model)
            converter.upsample(model, output)
            if (frame >= 5) {
                inputEnergy += energy(input)
                outputEnergy += energy(output)
                samplesMeasured += input.size
            }
        }

        val gain = sqrt(outputEnergy / inputEnergy)
        assertTrue("1 kHz round-trip gain was $gain", gain in 0.92..1.05)
        assertTrue("No samples measured", samplesMeasured > 0)
    }

    @Test
    fun frequenciesAboveNewNyquistAreRejectedBeforeDecimation() {
        val converter = FactorThreeSampleRateConverter(MODEL_FRAME)
        val input = ShortArray(NATIVE_FRAME)
        val model = ShortArray(MODEL_FRAME)
        var highBandEnergy = 0.0
        var inputEnergy = 0.0

        repeat(80) { frame ->
            fillTone(input, frame, frequencyHz = 12_000.0, amplitude = 10_000.0)
            converter.downsample(input, model)
            if (frame >= 5) {
                highBandEnergy += energy(model)
                inputEnergy += energy(input) / 3.0
            }
        }

        val residual = sqrt(highBandEnergy / inputEnergy)
        assertTrue("12 kHz alias residual was $residual", residual < 0.02)
    }

    @Test
    fun interpolationNeverEscapesFinalSafetyCeiling() {
        val converter = FactorThreeSampleRateConverter(MODEL_FRAME)
        val model = ShortArray(MODEL_FRAME) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val output = ShortArray(NATIVE_FRAME)

        repeat(10) { converter.upsample(model, output) }

        val peak = output.maxOf { abs(it.toInt()) }
        assertTrue("Interpolation peak was $peak", peak <= (32_767 * 0.921f).toInt())
    }

    private fun fillTone(
        destination: ShortArray,
        frame: Int,
        frequencyHz: Double,
        amplitude: Double,
    ) {
        for (index in destination.indices) {
            val sampleIndex = frame * destination.size + index
            destination[index] = (
                amplitude * sin(2.0 * PI * frequencyHz * sampleIndex / NATIVE_RATE)
                ).toInt().toShort()
        }
    }

    private fun energy(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample.toDouble()
        return sum
    }

    companion object {
        private const val MODEL_FRAME = 160
        private const val NATIVE_FRAME = MODEL_FRAME * 3
        private const val NATIVE_RATE = 48_000.0
    }
}
