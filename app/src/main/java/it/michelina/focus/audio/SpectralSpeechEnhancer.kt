package it.michelina.focus.audio

import android.content.Context
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A dependency-free, streaming speech enhancement baseline.
 *
 * This is deliberately a replaceable processor: it gives us a safe A/B harness and a stronger
 * baseline than raw gain while we benchmark neural denoisers and target-speaker models. It uses
 * a causal noise estimate, Wiener-style spectral gains and a speech-preserving gain floor.
 */
class SpectralSpeechEnhancer(
    private val sampleRate: Int = SAMPLE_RATE,
    context: Context? = null,
    voiceDetectorBackend: VoiceDetectorBackend = VoiceDetectorBackend.SILERO,
) : RealtimeAudioProcessor {
    override val frameSizeSamples: Int = HOP_SIZE
    override val algorithmLatencySamples: Int = HOP_SIZE
    private val voiceBinStart =
        (120f * FFT_SIZE / sampleRate).toInt().coerceIn(1, BIN_COUNT - 1)
    private val voiceBinEnd =
        (min(7_500f, sampleRate * 0.46f) * FFT_SIZE / sampleRate)
            .toInt()
            .coerceIn(voiceBinStart, BIN_COUNT - 1)
    private val fft = Radix2Fft(FFT_SIZE)
    private val real = FloatArray(FFT_SIZE)
    private val imaginary = FloatArray(FFT_SIZE)
    private val power = FloatArray(BIN_COUNT)
    private val noisePower = FloatArray(BIN_COUNT) { INITIAL_NOISE_POWER }
    private val smoothedGain = FloatArray(BIN_COUNT) { 1f }
    private val frequencySmoothedGain = FloatArray(BIN_COUNT) { 1f }
    private val previousInput = FloatArray(HOP_SIZE)
    private val overlap = FloatArray(HOP_SIZE)
    private val alignedDryFrame = FloatArray(HOP_SIZE)
    private val denoisedFrame = FloatArray(HOP_SIZE)
    private val conditionedFrame = FloatArray(HOP_SIZE)
    private val fittedFrame = FloatArray(HOP_SIZE)
    private val window = FloatArray(FFT_SIZE) { index ->
        sin(PI * (index + 0.5) / FFT_SIZE).toFloat()
    }

    private var framesSeen = 0
    private var processedFrames = 0L
    private var speechProbability = 0f
    private var highPassPreviousInput = 0f
    private var highPassPreviousOutput = 0f
    private val speechFitter = MultibandSpeechFitter(sampleRate)
    private val outputDynamics = SpeechOutputDynamics(sampleRate)
    private val voiceDetector = context?.let {
        NeuralVoiceDetector(it, sampleRate, voiceDetectorBackend)
    }

    fun reset() {
        real.fill(0f)
        imaginary.fill(0f)
        power.fill(0f)
        noisePower.fill(INITIAL_NOISE_POWER)
        smoothedGain.fill(1f)
        frequencySmoothedGain.fill(1f)
        previousInput.fill(0f)
        overlap.fill(0f)
        alignedDryFrame.fill(0f)
        denoisedFrame.fill(0f)
        conditionedFrame.fill(0f)
        fittedFrame.fill(0f)
        framesSeen = 0
        processedFrames = 0
        speechProbability = 0f
        highPassPreviousInput = 0f
        highPassPreviousOutput = 0f
        speechFitter.reset()
        outputDynamics.reset()
    }

    /** Processes exactly [HOP_SIZE] mono PCM16 samples. */
    override fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics {
        require(input.size >= HOP_SIZE && output.size >= HOP_SIZE)

        val inputRms = rms(input)
        val neuralVoiceEstimate = voiceDetector?.process(input)
        if (neuralVoiceEstimate != null) {
            speechProbability = neuralVoiceEstimate.probability
        }

        if (settings.mode == ProcessingMode.BYPASS) {
            speechFitter.reset()
            for (i in 0 until HOP_SIZE) {
                val sample = input[i] / 32768f
                output[i] = floatToLimitedPcm(outputDynamics.process(sample, settings.gainDb))
            }
            processedFrames++
            val outputRms = rms(output)
            return FrameProcessingMetrics(
                inputDbFs = amplitudeToDb(inputRms),
                outputDbFs = amplitudeToDb(outputRms),
                speechProbability = speechProbability,
                vadRawProbability = neuralVoiceEstimate?.rawProbability ?: speechProbability,
                vadSpeechDetected = neuralVoiceEstimate?.speechDetected ?: false,
                vadProcessedWindows = neuralVoiceEstimate?.processedWindows ?: 0,
                vadInferenceMs = neuralVoiceEstimate?.averageInferenceMs ?: 0f,
                vadModelName = neuralVoiceEstimate?.modelName ?: "SPECTRAL VAD",
                processedFrames = processedFrames,
                effectiveGainDb = if (inputRms > MIN_METRIC_RMS) {
                    amplitudeToDb(outputRms) - amplitudeToDb(inputRms)
                } else {
                    0f
                },
            )
        }

        for (i in 0 until HOP_SIZE) {
            alignedDryFrame[i] = previousInput[i]
            real[i] = previousInput[i] * window[i]
            real[i + HOP_SIZE] = (input[i] / 32768f) * window[i + HOP_SIZE]
            previousInput[i] = input[i] / 32768f
            imaginary[i] = 0f
            imaginary[i + HOP_SIZE] = 0f
        }

        fft.forward(real, imaginary)
        estimateSpectrumAndSpeechProbability()
        applySpectralGains(settings.denoiseStrength.coerceIn(0f, 1f))
        fft.inverse(real, imaginary)

        for (i in 0 until HOP_SIZE) {
            val reconstructed = real[i] * window[i] + overlap[i]
            overlap[i] = real[i + HOP_SIZE] * window[i + HOP_SIZE]
            denoisedFrame[i] = reconstructed
            conditionedFrame[i] = highPass(reconstructed)
        }

        val fitMetrics = speechFitter.process(
            input = conditionedFrame,
            output = fittedFrame,
            clarity = settings.clarity,
            quietSpeechBoostDb = settings.quietSpeechBoostDb,
            speechProbability = speechProbability,
            profile = settings.fittingProfile,
            enabled = true,
        )

        val dryRms = rms(alignedDryFrame)
        val denoisedRms = rms(denoisedFrame)
        val changedRms = rmsDifference(denoisedFrame, alignedDryFrame)
        var levelledSquareSum = 0.0
        for (i in 0 until HOP_SIZE) {
            val levelled = fittedFrame[i]
            levelledSquareSum += levelled * levelled
            output[i] = floatToLimitedPcm(outputDynamics.process(levelled, settings.gainDb))
        }
        val levelledRms = sqrt(levelledSquareSum / HOP_SIZE).toFloat()
        val outputRms = rms(output)
        framesSeen++
        processedFrames++
        return FrameProcessingMetrics(
            inputDbFs = amplitudeToDb(inputRms),
            outputDbFs = amplitudeToDb(outputRms),
            speechProbability = speechProbability,
            vadRawProbability = neuralVoiceEstimate?.rawProbability ?: speechProbability,
            vadSpeechDetected = neuralVoiceEstimate?.speechDetected ?: (speechProbability >= 0.5f),
            vadProcessedWindows = neuralVoiceEstimate?.processedWindows ?: framesSeen.toLong(),
            vadInferenceMs = neuralVoiceEstimate?.averageInferenceMs ?: 0f,
            vadModelName = neuralVoiceEstimate?.modelName ?: "SPECTRAL VAD",
            processedFrames = processedFrames,
            denoiseDeltaDb = if (framesSeen > 1 && dryRms > MIN_METRIC_RMS) {
                amplitudeToDb(denoisedRms) - amplitudeToDb(dryRms)
            } else {
                0f
            },
            signalChangedPercent = if (framesSeen > 1 && dryRms > MIN_METRIC_RMS) {
                (changedRms / dryRms * 100f).coerceIn(0f, 999f)
            } else {
                0f
            },
            presenceDeltaDb = fitMetrics.presenceDeltaDb,
            quietSpeechBoostDb = fitMetrics.quietSpeechBoostDb,
            effectiveGainDb = if (levelledRms > MIN_METRIC_RMS) {
                amplitudeToDb(outputRms) - amplitudeToDb(levelledRms)
            } else {
                0f
            },
        )
    }

    private fun estimateSpectrumAndSpeechProbability() {
        var logSnrSum = 0f
        var speechBins = 0

        for (bin in 0 until BIN_COUNT) {
            val currentPower = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            power[bin] = max(currentPower, EPSILON)

            if (bin in voiceBinStart..voiceBinEnd) {
                val ratio = power[bin] / max(noisePower[bin], EPSILON)
                logSnrSum += 10f * log10(max(ratio, EPSILON))
                speechBins++
            }
        }

        val averageSnrDb = if (speechBins == 0) -20f else logSnrSum / speechBins
        val instantaneousProbability = sigmoid((averageSnrDb - 1.5f) / 2.4f)
        if (voiceDetector == null) {
            speechProbability = 0.82f * speechProbability + 0.18f * instantaneousProbability
        }

        val initializing = framesSeen < NOISE_WARMUP_FRAMES
        val updateAlpha = when {
            initializing -> 0.82f
            speechProbability < 0.35f -> 0.93f
            else -> 0.9985f
        }

        for (bin in 0 until BIN_COUNT) {
            val candidate = updateAlpha * noisePower[bin] + (1f - updateAlpha) * power[bin]
            // Let the estimate rise slowly during speech, but follow falling room noise promptly.
            noisePower[bin] = if (power[bin] < noisePower[bin]) {
                0.88f * noisePower[bin] + 0.12f * power[bin]
            } else {
                candidate
            }.coerceAtLeast(EPSILON)
        }
    }

    private fun applySpectralGains(strength: Float) {
        for (bin in 0 until BIN_COUNT) {
            val posteriorSnr = max(power[bin] / max(noisePower[bin], EPSILON) - 1f, 0f)
            val wienerGain = posteriorSnr / (posteriorSnr + 1f)
            val frequencyHz = bin * sampleRate.toFloat() / FFT_SIZE
            val inSpeechBand = frequencyHz in 120f..7_500f
            val floor = if (inSpeechBand) {
                0.10f + 0.42f * speechProbability
            } else {
                0.06f + 0.18f * speechProbability
            }
            val denoised = max(floor, wienerGain)
            val target = (1f - strength) + strength * denoised
            val smoothing = if (target < smoothedGain[bin]) 0.72f else 0.42f
            smoothedGain[bin] = smoothing * smoothedGain[bin] + (1f - smoothing) * target
        }

        frequencySmoothedGain[0] = smoothedGain[0]
        for (bin in 1 until BIN_COUNT - 1) {
            frequencySmoothedGain[bin] =
                0.2f * smoothedGain[bin - 1] +
                    0.6f * smoothedGain[bin] +
                    0.2f * smoothedGain[bin + 1]
        }
        frequencySmoothedGain[BIN_COUNT - 1] = smoothedGain[BIN_COUNT - 1]

        for (bin in 0 until BIN_COUNT) {
            val gain = frequencySmoothedGain[bin]
            real[bin] *= gain
            imaginary[bin] *= gain
            if (bin != 0 && bin != FFT_SIZE / 2) {
                val mirror = FFT_SIZE - bin
                real[mirror] *= gain
                imaginary[mirror] *= gain
            }
        }
    }

    private fun highPass(input: Float): Float {
        val cutoff = 95f
        val rc = 1f / (2f * PI.toFloat() * cutoff)
        val dt = 1f / sampleRate
        val alpha = rc / (rc + dt)
        val output = alpha * (highPassPreviousOutput + input - highPassPreviousInput)
        highPassPreviousInput = input
        highPassPreviousOutput = output
        return output
    }

    private fun rms(samples: ShortArray): Float {
        var sum = 0.0
        for (i in 0 until HOP_SIZE) {
            val normalized = samples[i] / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / HOP_SIZE).toFloat()
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return sqrt(sum / samples.size).toFloat()
    }

    private fun rmsDifference(first: FloatArray, second: FloatArray): Float {
        var sum = 0.0
        for (index in first.indices) {
            val difference = first[index] - second[index]
            sum += difference * difference
        }
        return sqrt(sum / first.size).toFloat()
    }

    private fun amplitudeToDb(amplitude: Float): Float =
        (20f * log10(max(amplitude, 1e-6f))).coerceAtLeast(-120f)

    private fun sigmoid(value: Float): Float = (1f / (1f + exp(-value)))

    override fun close() {
        voiceDetector?.close()
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val HOP_SIZE = 512
        const val FFT_SIZE = 1_024
        private const val BIN_COUNT = FFT_SIZE / 2 + 1
        private const val NOISE_WARMUP_FRAMES = 18
        private const val INITIAL_NOISE_POWER = 1e-5f
        private const val EPSILON = 1e-12f
        private const val MIN_METRIC_RMS = 1e-5f
    }
}

internal class Radix2Fft(private val size: Int) {
    init {
        require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    fun forward(real: FloatArray, imaginary: FloatArray) = transform(real, imaginary, inverse = false)

    fun inverse(real: FloatArray, imaginary: FloatArray) {
        transform(real, imaginary, inverse = true)
        for (i in 0 until size) {
            real[i] /= size
            imaginary[i] /= size
        }
    }

    private fun transform(real: FloatArray, imaginary: FloatArray, inverse: Boolean) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realTemp = real[i]
                real[i] = real[j]
                real[j] = realTemp
                val imaginaryTemp = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginaryTemp
            }
        }

        var length = 2
        while (length <= size) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val phaseStepReal = cos(angle).toFloat()
            val phaseStepImaginary = sin(angle).toFloat()
            var start = 0
            while (start < size) {
                var phaseReal = 1f
                var phaseImaginary = 0f
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * phaseReal - imaginary[odd] * phaseImaginary
                    val oddImaginary = real[odd] * phaseImaginary + imaginary[odd] * phaseReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary

                    val nextPhaseReal = phaseReal * phaseStepReal - phaseImaginary * phaseStepImaginary
                    phaseImaginary = phaseReal * phaseStepImaginary + phaseImaginary * phaseStepReal
                    phaseReal = nextPhaseReal
                }
                start += length
            }
            length = length shl 1
        }
    }
}
