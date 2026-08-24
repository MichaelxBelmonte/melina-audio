package it.michelina.focus.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Causal 48↔16 kHz FIR converter with persistent state and no per-frame allocations. */
class FactorThreeSampleRateConverter(modelFrameSamples: Int) {
    private val downsampler = StreamingFirDecimatorByThree(ANTI_ALIAS_TAPS)
    private val upsampler = StreamingFirInterpolatorByThree(ANTI_ALIAS_TAPS)
    private val expectedNativeSamples = modelFrameSamples * FACTOR

    fun downsample(input48k: ShortArray, output16k: ShortArray) {
        require(input48k.size >= expectedNativeSamples)
        require(output16k.size * FACTOR == expectedNativeSamples)
        var outputIndex = 0
        for (index in 0 until expectedNativeSamples) {
            val filtered = downsampler.push(input48k[index] / 32768f)
            if (!filtered.isNaN()) output16k[outputIndex++] = floatToPcm(filtered)
        }
        check(outputIndex == output16k.size)
    }

    fun upsample(input16k: ShortArray, output48k: ShortArray) {
        require(input16k.size * FACTOR == expectedNativeSamples)
        require(output48k.size >= expectedNativeSamples)
        var outputIndex = 0
        for (sample in input16k) {
            upsampler.push(sample / 32768f)
            repeat(FACTOR) { phase ->
                output48k[outputIndex++] = floatToLimitedPcm(upsampler.output(phase) * FACTOR)
            }
        }
    }

    private fun floatToPcm(value: Float): Short =
        (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    companion object {
        private const val FACTOR = 3
        private const val TAP_COUNT = 63
        private const val CUTOFF_CYCLES_PER_SAMPLE = 0.155f
        const val LATENCY_48K_SAMPLES = TAP_COUNT - 1

        val ANTI_ALIAS_TAPS = FloatArray(TAP_COUNT).also { taps ->
            val middle = (TAP_COUNT - 1) / 2
            var sum = 0.0
            for (index in taps.indices) {
                val distance = index - middle
                val ideal = if (distance == 0) {
                    2.0 * CUTOFF_CYCLES_PER_SAMPLE
                } else {
                    sin(2.0 * PI * CUTOFF_CYCLES_PER_SAMPLE * distance) / (PI * distance)
                }
                val window = 0.42 -
                    0.5 * cos(2.0 * PI * index / (TAP_COUNT - 1)) +
                    0.08 * cos(4.0 * PI * index / (TAP_COUNT - 1))
                taps[index] = (ideal * window).toFloat()
                sum += taps[index]
            }
            for (index in taps.indices) taps[index] = (taps[index] / sum).toFloat()
        }
    }
}

/** Stores every input but only convolves when a decimated output is actually needed. */
class StreamingFirDecimatorByThree(private val taps: FloatArray) {
    private val history = FloatArray(taps.size)
    private var writeIndex = 0
    private var phase = 0

    fun push(value: Float): Float {
        history[writeIndex] = value
        writeIndex = (writeIndex + 1) % history.size
        phase++
        if (phase != 3) return Float.NaN
        phase = 0
        var result = 0f
        var historyIndex = if (writeIndex == 0) history.lastIndex else writeIndex - 1
        for (tap in taps) {
            result += tap * history[historyIndex]
            historyIndex = if (historyIndex == 0) history.lastIndex else historyIndex - 1
        }
        return result
    }
}

/** Polyphase interpolator: 21 useful taps per output instead of filtering inserted zeroes. */
class StreamingFirInterpolatorByThree(private val taps: FloatArray) {
    private val history = FloatArray((taps.size + 2) / 3)
    private var writeIndex = 0

    fun push(value: Float) {
        history[writeIndex] = value
        writeIndex = (writeIndex + 1) % history.size
    }

    fun output(phase: Int): Float {
        require(phase in 0..2)
        var result = 0f
        var tapIndex = phase
        var historyIndex = if (writeIndex == 0) history.lastIndex else writeIndex - 1
        while (tapIndex < taps.size) {
            result += taps[tapIndex] * history[historyIndex]
            tapIndex += 3
            historyIndex = if (historyIndex == 0) history.lastIndex else historyIndex - 1
        }
        return result
    }
}
