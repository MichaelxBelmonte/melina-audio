package it.michelina.focus.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max

internal data class NeuralModelSpec(
    val backend: ProcessorBackend,
    val displayName: String,
    val assetPath: String,
    val sampleRate: Int,
    val frameShiftSamples: Int,
)

internal fun neuralModelSpec(backend: ProcessorBackend): NeuralModelSpec = when (backend) {
    ProcessorBackend.GTCRN_FAST -> NeuralModelSpec(
        backend = backend,
        displayName = "GTCRN Fast",
        assetPath = "models/gtcrn_simple.onnx",
        sampleRate = 16_000,
        frameShiftSamples = 256,
    )
    ProcessorBackend.DPDFNET2_BALANCED -> NeuralModelSpec(
        backend = backend,
        displayName = "DPDFNet2 Balanced",
        assetPath = "models/dpdfnet2.onnx",
        sampleRate = 16_000,
        frameShiftSamples = 160,
    )
    ProcessorBackend.DPDFNET4_STRONG -> NeuralModelSpec(
        backend = backend,
        displayName = "DPDFNet4 Strong",
        assetPath = "models/dpdfnet4.onnx",
        sampleRate = 16_000,
        frameShiftSamples = 160,
    )
    ProcessorBackend.DPDFNET8_SPEECH -> NeuralModelSpec(
        backend = backend,
        displayName = "DPDFNet8 Speech",
        assetPath = "models/dpdfnet8.onnx",
        sampleRate = 16_000,
        frameShiftSamples = 160,
    )
    ProcessorBackend.DPDFNET_HQ -> NeuralModelSpec(
        backend = backend,
        displayName = "DPDFNet2 HQ",
        assetPath = "models/dpdfnet2_48khz_hr.onnx",
        sampleRate = 48_000,
        frameShiftSamples = 480,
    )
    ProcessorBackend.CLASSIC_DSP,
    ProcessorBackend.RNNOISE_NATIVE,
    ProcessorBackend.ULUNAS_STREAM,
    ProcessorBackend.DEEPFILTER3_HQ -> error("This backend does not use sherpa-onnx")
}

/**
 * Streaming neural speech enhancement backed by sherpa-onnx.
 *
 * The model is always advanced, including during bypass, so switching A/B modes does not reset its
 * recurrent state. The dry path is delayed by one model hop to keep comparisons time-aligned.
 */
internal class NeuralSpeechEnhancer(
    context: Context,
    val backend: ProcessorBackend,
    voiceDetectorBackend: VoiceDetectorBackend,
) : RealtimeAudioProcessor {
    private val spec = neuralModelSpec(backend)
    private val denoiser: OnlineSpeechDenoiser
    override val frameSizeSamples: Int
    override val algorithmLatencySamples: Int

    private val inputFloat: FloatArray
    private val neuralFrame: FloatArray
    private val delayedDryFrame: FloatArray
    private val mixedFrame: FloatArray
    private val dryDelay: FloatArray
    private val outputQueue: FloatRingBuffer
    private val conditioner: NeuralOutputConditioner

    private var firstInference = true
    private var released = false
    private var processedFrames = 0L
    private var lastMode = ProcessingMode.VOICE_FOCUS
    private val voiceDetector = NeuralVoiceDetector(
        context = context,
        inputSampleRate = spec.sampleRate,
        backend = voiceDetectorBackend,
    )

    init {
        val modelConfig = OfflineSpeechDenoiserModelConfig().apply {
            when (backend) {
                ProcessorBackend.GTCRN_FAST -> {
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig().apply {
                        model = spec.assetPath
                    }
                }
                ProcessorBackend.DPDFNET2_BALANCED,
                ProcessorBackend.DPDFNET4_STRONG,
                ProcessorBackend.DPDFNET8_SPEECH,
                ProcessorBackend.DPDFNET_HQ -> {
                    dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig().apply {
                        model = spec.assetPath
                        attenuationLimitDb = 0f
                    }
                }
                ProcessorBackend.CLASSIC_DSP,
                ProcessorBackend.RNNOISE_NATIVE,
                ProcessorBackend.ULUNAS_STREAM,
                ProcessorBackend.DEEPFILTER3_HQ -> error("Invalid neural backend")
            }
            numThreads = when (backend) {
                ProcessorBackend.DPDFNET4_STRONG,
                ProcessorBackend.DPDFNET8_SPEECH -> 2
                else -> 1
            }
            debug = false
            provider = "cpu"
        }
        val config = OnlineSpeechDenoiserConfig().apply { model = modelConfig }
        denoiser = OnlineSpeechDenoiser(context.applicationContext.assets, config)

        check(denoiser.sampleRate == spec.sampleRate) {
            "${spec.displayName}: ${denoiser.sampleRate} Hz instead of ${spec.sampleRate} Hz"
        }
        frameSizeSamples = denoiser.frameShiftInSamples
        check(frameSizeSamples == spec.frameShiftSamples) {
            "${spec.displayName}: hop $frameSizeSamples instead of ${spec.frameShiftSamples} samples"
        }
        algorithmLatencySamples = frameSizeSamples

        inputFloat = FloatArray(frameSizeSamples)
        neuralFrame = FloatArray(frameSizeSamples)
        delayedDryFrame = FloatArray(frameSizeSamples)
        mixedFrame = FloatArray(frameSizeSamples)
        dryDelay = FloatArray(algorithmLatencySamples)
        outputQueue = FloatRingBuffer(frameSizeSamples * 8)
        conditioner = NeuralOutputConditioner(spec.sampleRate)
    }

    override fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics {
        check(!released) { "${spec.displayName} has already been released" }
        require(input.size >= frameSizeSamples && output.size >= frameSizeSamples)

        var inputSquareSum = 0.0
        for (index in 0 until frameSizeSamples) {
            val value = input[index] / 32768f
            inputFloat[index] = value
            inputSquareSum += value * value
        }
        val inputRms = kotlin.math.sqrt(inputSquareSum / frameSizeSamples).toFloat()
        val voiceEstimate = voiceDetector.process(input)

        val denoised = denoiser.run(inputFloat, spec.sampleRate).samples
        if (firstInference) {
            // Streaming STFT models hold back their first hop. Make that latency explicit.
            repeat((frameSizeSamples - denoised.size).coerceIn(0, frameSizeSamples)) {
                outputQueue.add(0f)
            }
            firstInference = false
        }
        outputQueue.addAll(denoised)
        for (index in 0 until frameSizeSamples) {
            neuralFrame[index] = outputQueue.removeOrZero()
        }

        for (index in 0 until frameSizeSamples) {
            delayedDryFrame[index] = if (index < algorithmLatencySamples) {
                dryDelay[index]
            } else {
                inputFloat[index - algorithmLatencySamples]
            }
        }
        val dryTailStart = frameSizeSamples - algorithmLatencySamples
        for (index in dryDelay.indices) {
            dryDelay[index] = inputFloat[dryTailStart + index]
        }

        if (settings.mode != lastMode) {
            conditioner.reset()
            lastMode = settings.mode
        }

        val wet = if (settings.mode == ProcessingMode.VOICE_FOCUS) {
            settings.denoiseStrength.coerceIn(0f, 1f)
        } else {
            0f
        }
        for (index in 0 until frameSizeSamples) {
            val dry = delayedDryFrame[index]
            mixedFrame[index] = dry + (neuralFrame[index] - dry) * wet
        }

        val dryRms = rms(delayedDryFrame)
        val mixedRms = rms(mixedFrame)
        val changedRms = rmsDifference(mixedFrame, delayedDryFrame)
        val conditioningMetrics = conditioner.process(
            input = mixedFrame,
            output = output,
            gainDb = settings.gainDb,
            clarity = settings.clarity,
            quietSpeechBoostDb = settings.quietSpeechBoostDb,
            speechProbability = voiceEstimate.probability,
            fittingProfile = settings.fittingProfile,
            voiceShaping = settings.mode == ProcessingMode.VOICE_FOCUS,
        )
        processedFrames++

        return FrameProcessingMetrics(
            inputDbFs = amplitudeToDb(inputRms),
            outputDbFs = amplitudeToDb(rms(output)),
            speechProbability = voiceEstimate.probability,
            vadRawProbability = voiceEstimate.rawProbability,
            vadSpeechDetected = voiceEstimate.speechDetected,
            vadProcessedWindows = voiceEstimate.processedWindows,
            vadInferenceMs = voiceEstimate.averageInferenceMs,
            vadModelName = voiceEstimate.modelName,
            processedFrames = processedFrames,
            denoiseDeltaDb = if (dryRms > MIN_METRIC_RMS) {
                amplitudeToDb(mixedRms) - amplitudeToDb(dryRms)
            } else {
                0f
            },
            signalChangedPercent = if (dryRms > MIN_METRIC_RMS) {
                (changedRms / dryRms * 100f).coerceIn(0f, 999f)
            } else {
                0f
            },
            presenceDeltaDb = conditioningMetrics.presenceDeltaDb,
            quietSpeechBoostDb = conditioningMetrics.quietSpeechBoostDb,
            effectiveGainDb = conditioningMetrics.effectiveGainDb,
        )
    }

    override fun close() {
        if (released) return
        released = true
        voiceDetector.close()
        denoiser.release()
    }

    private fun rms(samples: ShortArray): Float {
        var sum = 0.0
        for (index in 0 until frameSizeSamples) {
            val normalized = samples[index] / 32768.0
            sum += normalized * normalized
        }
        return kotlin.math.sqrt(sum / frameSizeSamples).toFloat()
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }

    private fun rmsDifference(first: FloatArray, second: FloatArray): Float {
        var sum = 0.0
        for (index in first.indices) {
            val difference = first[index] - second[index]
            sum += difference * difference
        }
        return kotlin.math.sqrt(sum / first.size).toFloat()
    }

    private fun amplitudeToDb(amplitude: Float): Float =
        (20f * log10(max(amplitude, 1e-6f))).coerceAtLeast(-120f)

    companion object {
        private const val MIN_METRIC_RMS = 1e-5f
    }
}

private class FloatRingBuffer(capacity: Int) {
    private val values = FloatArray(capacity)
    private var readIndex = 0
    private var size = 0

    fun add(value: Float) {
        check(size < values.size) { "Neural output buffer overflow" }
        values[(readIndex + size) % values.size] = value
        size++
    }

    fun addAll(source: FloatArray) {
        for (value in source) add(value)
    }

    fun removeOrZero(): Float {
        if (size == 0) return 0f
        val value = values[readIndex]
        readIndex = (readIndex + 1) % values.size
        size--
        return value
    }
}

internal data class ConditioningMetrics(
    val presenceDeltaDb: Float,
    val quietSpeechBoostDb: Float,
    val effectiveGainDb: Float,
)

internal class NeuralOutputConditioner(private val sampleRate: Int) {
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
