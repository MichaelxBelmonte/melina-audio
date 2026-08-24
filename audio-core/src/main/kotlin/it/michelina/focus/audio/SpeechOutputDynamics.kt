package it.michelina.focus.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Stateful output compressor and peak limiter.
 *
 * Unlike a static sample-by-sample waveshaper, this processor follows the signal envelope and
 * changes gain smoothly. That avoids adding unnecessary harmonic distortion to already fragile
 * consonants while still leaving a hard digital ceiling as a final safety net.
 */
class SpeechOutputDynamics(sampleRate: Int) {
    private val envelopeAttack = coefficient(sampleRate, 0.006f)
    private val envelopeRelease = coefficient(sampleRate, 0.120f)
    private val compressionAttack = coefficient(sampleRate, 0.010f)
    private val compressionRelease = coefficient(sampleRate, 0.180f)
    private val limiterRelease = coefficient(sampleRate, 0.045f)

    private var envelope = 1e-6f
    private var compressionGainDb = 0f
    private var compressionGain = 1f
    private var limiterGain = 1f

    fun process(input: Float, requestedGainDb: Float): Float {
        val staticGainDb = requestedGainDb.coerceIn(0f, MAX_SOFTWARE_GAIN_DB)
        val boosted = input * dbToLinear(staticGainDb)
        val magnitude = abs(boosted)
        val envelopeCoefficient = if (magnitude > envelope) envelopeAttack else envelopeRelease
        envelope = envelopeCoefficient * envelope + (1f - envelopeCoefficient) * magnitude

        val levelDb = amplitudeToDb(envelope)
        val targetCompressionDb = compressionReductionDb(levelDb)
        val compressionCoefficient = if (targetCompressionDb < compressionGainDb) {
            compressionAttack
        } else {
            compressionRelease
        }
        compressionGainDb = compressionCoefficient * compressionGainDb +
            (1f - compressionCoefficient) * targetCompressionDb
        compressionGain = dbToLinear(compressionGainDb)

        val compressed = boosted * compressionGain
        val requiredLimiterGain = if (abs(compressed) > LIMITER_CEILING) {
            LIMITER_CEILING / max(abs(compressed), 1e-6f)
        } else {
            1f
        }
        limiterGain = if (requiredLimiterGain < limiterGain) {
            requiredLimiterGain
        } else {
            limiterRelease * limiterGain + (1f - limiterRelease) * requiredLimiterGain
        }
        return (compressed * limiterGain).coerceIn(-LIMITER_CEILING, LIMITER_CEILING)
    }

    fun reset() {
        envelope = 1e-6f
        compressionGainDb = 0f
        compressionGain = 1f
        limiterGain = 1f
    }

    private fun compressionReductionDb(levelDb: Float): Float {
        val kneeStart = COMPRESSOR_THRESHOLD_DB - COMPRESSOR_KNEE_DB * 0.5f
        val kneeEnd = COMPRESSOR_THRESHOLD_DB + COMPRESSOR_KNEE_DB * 0.5f
        if (levelDb <= kneeStart) return 0f

        val fullReduction = (COMPRESSOR_THRESHOLD_DB +
            (levelDb - COMPRESSOR_THRESHOLD_DB) / COMPRESSOR_RATIO) - levelDb
        if (levelDb >= kneeEnd) return fullReduction

        val position = ((levelDb - kneeStart) / COMPRESSOR_KNEE_DB).coerceIn(0f, 1f)
        val smoothPosition = position * position * (3f - 2f * position)
        return fullReduction * smoothPosition
    }

    private fun coefficient(sampleRate: Int, seconds: Float): Float =
        exp(-1.0 / (sampleRate * seconds)).toFloat()

    private fun amplitudeToDb(value: Float): Float = 20f * log10(max(value, 1e-6f))

    private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    companion object {
        const val MAX_SOFTWARE_GAIN_DB = 12f
        const val LIMITER_CEILING = 0.92f
        private const val COMPRESSOR_THRESHOLD_DB = -12f
        private const val COMPRESSOR_KNEE_DB = 6f
        private const val COMPRESSOR_RATIO = 3f
    }
}

fun floatToLimitedPcm(value: Float): Short =
    (value.coerceIn(-SpeechOutputDynamics.LIMITER_CEILING, SpeechOutputDynamics.LIMITER_CEILING) *
        32767f).toInt().toShort()
