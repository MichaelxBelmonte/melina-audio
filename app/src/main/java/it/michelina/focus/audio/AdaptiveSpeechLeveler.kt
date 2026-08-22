package it.michelina.focus.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Raises weak speech more than already-loud speech. The speech gate is deliberately conservative:
 * it avoids turning a quiet room-noise estimate into a constant 12 dB boost.
 */
internal class AdaptiveSpeechLeveler(sampleRate: Int) {
    private val envelopeAttack = coefficient(sampleRate, 0.012f)
    private val envelopeRelease = coefficient(sampleRate, 0.240f)
    private val controlInterval = max(1, sampleRate / CONTROL_RATE_HZ)
    private val boostAttack = coefficient(sampleRate, 0.120f, controlInterval)
    private val boostRelease = coefficient(sampleRate, 0.035f, controlInterval)

    private var envelope = 1e-5f
    private var appliedBoostDb = 0f
    private var linearBoost = 1f
    private var samplesUntilControl = 0

    val currentBoostDb: Float
        get() = appliedBoostDb

    fun process(
        input: Float,
        speechProbability: Float,
        requestedBoostDb: Float,
        enabled: Boolean,
    ): Float {
        val magnitude = abs(input)
        val envelopeCoefficient = if (magnitude > envelope) envelopeAttack else envelopeRelease
        envelope = envelopeCoefficient * envelope + (1f - envelopeCoefficient) * magnitude

        if (samplesUntilControl <= 0) {
            val targetBoostDb = if (enabled) {
                val levelDb = 20f * log10(max(envelope, 1e-6f))
                val quietFactor = ((QUIET_CEILING_DBFS - levelDb) / QUIET_RANGE_DB)
                    .coerceIn(0f, 1f)
                val speechGate = ((speechProbability - SPEECH_GATE_START) / SPEECH_GATE_RANGE)
                    .coerceIn(0f, 1f)
                requestedBoostDb.coerceIn(0f, MAX_QUIET_SPEECH_BOOST_DB) *
                    quietFactor * speechGate
            } else {
                0f
            }

            val boostCoefficient = if (targetBoostDb > appliedBoostDb) boostAttack else boostRelease
            appliedBoostDb = boostCoefficient * appliedBoostDb +
                (1f - boostCoefficient) * targetBoostDb
            linearBoost = 10f.pow(appliedBoostDb / 20f)
            samplesUntilControl = controlInterval
        }
        samplesUntilControl--
        return input * linearBoost
    }

    fun reset() {
        envelope = 1e-5f
        appliedBoostDb = 0f
        linearBoost = 1f
        samplesUntilControl = 0
    }

    private fun coefficient(sampleRate: Int, seconds: Float, samples: Int = 1): Float =
        exp(-samples.toDouble() / (sampleRate * seconds)).toFloat()

    companion object {
        private const val QUIET_CEILING_DBFS = -26f
        private const val QUIET_RANGE_DB = 20f
        private const val SPEECH_GATE_START = 0.35f
        private const val SPEECH_GATE_RANGE = 0.45f
        private const val CONTROL_RATE_HZ = 1_000
    }
}
