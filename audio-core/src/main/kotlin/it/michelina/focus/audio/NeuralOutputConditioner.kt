package it.michelina.focus.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max

data class ConditioningMetrics(
    val presenceDeltaDb: Float,
    val quietSpeechBoostDb: Float,
    val effectiveGainDb: Float,
)

/** Shared post-processing used after every neural or native denoising backend. */
class NeuralOutputConditioner(private val sampleRate: Int) {
    private var previousHighPassInput = 0f
    private var previousHighPassOutput = 0f
    private val speechFitter = MultibandSpeechFitter(sampleRate)
    private val outputDynamics = SpeechOutputDynamics(sampleRate)
    private var highPassedFrame = FloatArray(0)
    private var fittedFrame = FloatArray(0)

    fun process(
        input: FloatArray,
        output: ShortArray,
        gainDb: Float,
        clarity: Float,
        quietSpeechBoostDb: Float,
        speechProbability: Float,
        fittingProfile: FittingProfile,
        voiceShaping: Boolean,
    ): ConditioningMetrics {
        if (highPassedFrame.size != input.size) {
            highPassedFrame = FloatArray(input.size)
            fittedFrame = FloatArray(input.size)
        }
        var outputSquareSum = 0.0
        for (index in input.indices) {
            highPassedFrame[index] = if (voiceShaping) highPass(input[index]) else input[index]
        }
        val fitMetrics = speechFitter.process(
            input = highPassedFrame,
            output = fittedFrame,
            clarity = clarity,
            quietSpeechBoostDb = quietSpeechBoostDb,
            speechProbability = speechProbability,
            profile = fittingProfile,
            enabled = voiceShaping,
        )
        var fittedSquareSum = 0.0
        for (index in input.indices) {
            val fitted = fittedFrame[index]
            val amplified = outputDynamics.process(fitted, gainDb)
            fittedSquareSum += fitted * fitted
            outputSquareSum += amplified * amplified
            output[index] = floatToLimitedPcm(amplified)
        }
        val fittedRms = kotlin.math.sqrt(fittedSquareSum / input.size).toFloat()
        val outputRms = kotlin.math.sqrt(outputSquareSum / input.size).toFloat()
        return ConditioningMetrics(
            presenceDeltaDb = fitMetrics.presenceDeltaDb,
            quietSpeechBoostDb = fitMetrics.quietSpeechBoostDb,
            effectiveGainDb = if (fittedRms > MIN_METRIC_RMS) {
                levelDb(outputRms) - levelDb(fittedRms)
            } else {
                0f
            },
        )
    }

    fun reset() {
        previousHighPassInput = 0f
        previousHighPassOutput = 0f
        speechFitter.reset()
        outputDynamics.reset()
    }

    private fun highPass(input: Float): Float {
        val rc = 1f / (2f * PI.toFloat() * HIGH_PASS_HZ)
        val dt = 1f / sampleRate
        val alpha = rc / (rc + dt)
        val output = alpha * (previousHighPassOutput + input - previousHighPassInput)
        previousHighPassInput = input
        previousHighPassOutput = output
        return output
    }

    private fun levelDb(amplitude: Float): Float = 20f * log10(max(amplitude, 1e-6f))

    companion object {
        private const val HIGH_PASS_HZ = 95f
        private const val MIN_METRIC_RMS = 1e-5f
    }
}
