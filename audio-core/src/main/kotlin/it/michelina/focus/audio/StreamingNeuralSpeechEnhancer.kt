package it.michelina.focus.audio

import kotlin.math.log10
import kotlin.math.max

data class NeuralModelSpec(
    val backend: ProcessorBackend,
    val displayName: String,
    val assetPath: String,
    val sampleRate: Int,
    val frameShiftSamples: Int,
)

fun neuralModelSpec(backend: ProcessorBackend): NeuralModelSpec = when (backend) {
    ProcessorBackend.GTCRN_FAST -> NeuralModelSpec(
        backend, "GTCRN Fast", "models/gtcrn_simple.onnx", 16_000, 256,
    )
    ProcessorBackend.DPDFNET2_BALANCED -> NeuralModelSpec(
        backend, "DPDFNet2 Balanced", "models/dpdfnet2.onnx", 16_000, 160,
    )
    ProcessorBackend.DPDFNET4_STRONG -> NeuralModelSpec(
        backend, "DPDFNet4 Strong", "models/dpdfnet4.onnx", 16_000, 160,
    )
    ProcessorBackend.DPDFNET8_SPEECH -> NeuralModelSpec(
        backend, "DPDFNet8 Speech", "models/dpdfnet8.onnx", 16_000, 160,
    )
    ProcessorBackend.DPDFNET_HQ -> NeuralModelSpec(
        backend, "DPDFNet2 HQ", "models/dpdfnet2_48khz_hr.onnx", 48_000, 480,
    )
    else -> error("${backend.name} does not use sherpa-onnx")
}

/** Platform adapter for sherpa-onnx or another compatible streaming denoiser runtime. */
interface StreamingSpeechDenoiser : AutoCloseable {
    val sampleRate: Int
    val frameShiftSamples: Int
    fun run(input: FloatArray): FloatArray
}

/**
 * Platform-neutral real-time neural pipeline. Native runtimes only supply denoised float frames;
 * alignment, wet/dry mixing, hearing fitting, dynamics and telemetry remain shared everywhere.
 */
class StreamingNeuralSpeechEnhancer(
    val backend: ProcessorBackend,
    private val denoiser: StreamingSpeechDenoiser,
    private val voiceDetector: VoiceActivityDetector,
) : RealtimeAudioProcessor {
    private val spec = neuralModelSpec(backend)
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

    init {
        check(denoiser.sampleRate == spec.sampleRate) {
            "${spec.displayName}: ${denoiser.sampleRate} Hz instead of ${spec.sampleRate} Hz"
        }
        frameSizeSamples = denoiser.frameShiftSamples
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
        val denoised = denoiser.run(inputFloat)
        if (firstInference) {
            repeat((frameSizeSamples - denoised.size).coerceIn(0, frameSizeSamples)) {
                outputQueue.add(0f)
            }
            firstInference = false
        }
        outputQueue.addAll(denoised)
        for (index in 0 until frameSizeSamples) neuralFrame[index] = outputQueue.removeOrZero()

        for (index in 0 until frameSizeSamples) {
            delayedDryFrame[index] = if (index < algorithmLatencySamples) {
                dryDelay[index]
            } else {
                inputFloat[index - algorithmLatencySamples]
            }
        }
        val dryTailStart = frameSizeSamples - algorithmLatencySamples
        for (index in dryDelay.indices) dryDelay[index] = inputFloat[dryTailStart + index]

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
        val conditioning = conditioner.process(
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
            presenceDeltaDb = conditioning.presenceDeltaDb,
            quietSpeechBoostDb = conditioning.quietSpeechBoostDb,
            effectiveGainDb = conditioning.effectiveGainDb,
        )
    }

    override fun close() {
        if (released) return
        released = true
        voiceDetector.close()
        denoiser.close()
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
