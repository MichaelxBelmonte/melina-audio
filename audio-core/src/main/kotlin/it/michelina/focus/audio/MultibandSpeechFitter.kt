package it.michelina.focus.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

data class SpeechFitMetrics(
    val presenceDeltaDb: Float,
    val quietSpeechBoostDb: Float,
)

/**
 * Seven-band, perfectly reconstructing analysis bank for speech-oriented fitting.
 *
 * Six independent one-pole low-pass signals are differenced into seven bands. With every gain at
 * 0 dB the bands sum exactly to the input sample, so enabling the block at neutral settings does
 * not colour the signal. Presence weights favour consonant bands; weak-speech compression is
 * independently envelope-controlled per band and gated by the neural VAD.
 */
class MultibandSpeechFitter(private val sampleRate: Int) {
    private val lowPasses = floatArrayOf(250f, 500f, 1_000f, 2_000f, 3_500f, 6_000f).map { requested ->
        OnePoleLowPass(sampleRate, requested.coerceAtMost(sampleRate * 0.42f))
    }
    private val levelers = Array(BAND_COUNT) { AdaptiveSpeechLeveler(sampleRate) }
    private val low = FloatArray(lowPasses.size)
    private val bands = FloatArray(BAND_COUNT)
    private val presenceSmoothing = exp(-1.0 / (sampleRate * 0.045)).toFloat()
    private val presenceGains = FloatArray(BAND_COUNT) { 1f }
    private val targetPresenceGains = FloatArray(BAND_COUNT) { 1f }

    fun process(
        input: FloatArray,
        output: FloatArray,
        clarity: Float,
        quietSpeechBoostDb: Float,
        speechProbability: Float,
        profile: FittingProfile,
        enabled: Boolean,
    ): SpeechFitMetrics {
        require(output.size >= input.size)
        if (!enabled) {
            input.copyInto(output, endIndex = input.size)
            resetDynamics()
            presenceGains.fill(1f)
            return SpeechFitMetrics(0f, 0f)
        }

        val boundedClarity = clarity.coerceIn(0f, 1f)
        val presenceWeights = presenceWeights(profile)
        for (band in 0 until BAND_COUNT) {
            targetPresenceGains[band] = dbToLinear(
                boundedClarity * MAX_PRESENCE_BOOST_DB * presenceWeights[band],
            )
        }

        var inputSquareSum = 0.0
        var presenceSquareSum = 0.0
        var levelledSquareSum = 0.0
        for (index in input.indices) {
            val sample = input[index]
            inputSquareSum += sample * sample

            for (filterIndex in lowPasses.indices) {
                low[filterIndex] = lowPasses[filterIndex].process(sample)
            }
            bands[0] = low[0]
            for (band in 1 until low.size) bands[band] = low[band] - low[band - 1]
            bands[BAND_COUNT - 1] = sample - low.last()

            var presenceSample = 0f
            var levelledSample = 0f
            for (band in 0 until BAND_COUNT) {
                presenceGains[band] = presenceSmoothing * presenceGains[band] +
                    (1f - presenceSmoothing) * targetPresenceGains[band]
                val shaped = bands[band] * presenceGains[band]
                val levelled = levelers[band].process(
                    input = shaped,
                    speechProbability = speechProbability,
                    requestedBoostDb = quietSpeechBoostDb * QUIET_WEIGHTS[band],
                    enabled = true,
                )
                presenceSample += shaped
                levelledSample += levelled
            }
            presenceSquareSum += presenceSample * presenceSample
            levelledSquareSum += levelledSample * levelledSample
            output[index] = levelledSample
        }

        val inputRms = rms(inputSquareSum, input.size)
        val presenceRms = rms(presenceSquareSum, input.size)
        val levelledRms = rms(levelledSquareSum, input.size)
        return SpeechFitMetrics(
            presenceDeltaDb = if (inputRms > MIN_METRIC_RMS) {
                levelDb(presenceRms) - levelDb(inputRms)
            } else {
                0f
            },
            quietSpeechBoostDb = if (presenceRms > MIN_METRIC_RMS) {
                levelDb(levelledRms) - levelDb(presenceRms)
            } else {
                0f
            },
        )
    }

    fun reset() {
        lowPasses.forEach(OnePoleLowPass::reset)
        resetDynamics()
        presenceGains.fill(1f)
        targetPresenceGains.fill(1f)
    }

    private fun resetDynamics() = levelers.forEach(AdaptiveSpeechLeveler::reset)

    private fun rms(squareSum: Double, count: Int): Float =
        kotlin.math.sqrt(squareSum / count).toFloat()

    private fun levelDb(amplitude: Float): Float = 20f * log10(max(amplitude, 1e-6f))

    private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    companion object {
        const val BAND_COUNT = 7
        private val NATURAL_WEIGHTS = floatArrayOf(0f, 0.04f, 0.14f, 0.38f, 0.70f, 0.56f, 0.22f)
        private val SPEECH_WEIGHTS = floatArrayOf(0f, 0.07f, 0.25f, 0.62f, 1f, 0.84f, 0.38f)
        private val CONSONANT_WEIGHTS = floatArrayOf(0f, 0.02f, 0.10f, 0.34f, 0.82f, 1f, 0.64f)
        private val QUIET_WEIGHTS = floatArrayOf(0.12f, 0.28f, 0.55f, 0.84f, 1f, 0.78f, 0.38f)
        private const val MIN_METRIC_RMS = 1e-5f

        private fun presenceWeights(profile: FittingProfile): FloatArray = when (profile) {
            FittingProfile.NATURAL -> NATURAL_WEIGHTS
            FittingProfile.SPEECH -> SPEECH_WEIGHTS
            FittingProfile.CONSONANTS -> CONSONANT_WEIGHTS
        }
    }
}

private class OnePoleLowPass(sampleRate: Int, frequencyHz: Float) {
    private val alpha = 1f - exp(-2.0 * PI * frequencyHz / sampleRate).toFloat()
    private var state = 0f

    fun process(input: Float): Float {
        state += alpha * (input - state)
        return state
    }

    fun reset() {
        state = 0f
    }
}
