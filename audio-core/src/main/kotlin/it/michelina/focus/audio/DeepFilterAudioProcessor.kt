package it.michelina.focus.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Evaluates the official libDF streaming C API for one 48 kHz frame. */
interface DeepFilterInference : AutoCloseable {
    val frameSizeSamples: Int
    fun process(input: ShortArray, output: FloatArray): Float
    fun setParameters(attenuationLimitDb: Float, postFilterBeta: Float)
}

/** Shared DeepFilterNet alignment, wet/dry mixing, VAD, fitting, dynamics, and telemetry. */
class DeepFilterAudioProcessor(
    private val inference: DeepFilterInference,
    private val voiceDetector: VoiceActivityDetector,
) : RealtimeAudioProcessor {
    private val enhancedFrame: FloatArray
    private val delayedDry: FloatArray
    private val alignedDry: FloatArray
    private val mixedFrame: FloatArray
    private val delayedVad = FloatArray(LATENCY_FRAMES)
    private var delayPosition = 0
    private var vadDelayPosition = 0
    private val conditioner = NeuralOutputConditioner(SAMPLE_RATE)
    private var processedFrames = 0L
    private var lastMode = ProcessingMode.VOICE_FOCUS
    private var released = false

    override val frameSizeSamples: Int = inference.frameSizeSamples
    override val algorithmLatencySamples: Int

    init {
        check(frameSizeSamples == FRAME_SIZE) {
            "DeepFilterNet3: frame $frameSizeSamples instead of $FRAME_SIZE samples"
        }
        algorithmLatencySamples = frameSizeSamples * LATENCY_FRAMES
        enhancedFrame = FloatArray(frameSizeSamples)
        delayedDry = FloatArray(algorithmLatencySamples)
        alignedDry = FloatArray(frameSizeSamples)
        mixedFrame = FloatArray(frameSizeSamples)
        inference.setParameters(DEFAULT_ATTENUATION_LIMIT_DB, 0f)
    }

    override fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics {
        check(!released) { "DeepFilterNet3 has already been released" }
        require(input.size >= frameSizeSamples && output.size >= frameSizeSamples)

        val localSnrDb = inference.process(input, enhancedFrame)
        check(localSnrDb > NATIVE_ERROR_SENTINEL) { "DeepFilterNet3: native inference failed" }
        val voice = voiceDetector.process(input)
        val alignedVoiceProbability = delayedVad[vadDelayPosition]
        delayedVad[vadDelayPosition] = voice.probability
        vadDelayPosition = (vadDelayPosition + 1) % delayedVad.size

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
            val dry = delayedDry[delayPosition]
            alignedDry[index] = dry
            delayedDry[delayPosition] = input[index] / 32768f
            delayPosition = (delayPosition + 1) % delayedDry.size
            mixedFrame[index] = dry + (enhancedFrame[index] - dry) * wet
        }

        val dryRms = rms(alignedDry)
        val mixedRms = rms(mixedFrame)
        val changedRms = rmsDifference(mixedFrame, alignedDry)
        val conditioning = conditioner.process(
            input = mixedFrame,
            output = output,
            gainDb = settings.gainDb,
            clarity = settings.clarity,
            quietSpeechBoostDb = settings.quietSpeechBoostDb,
            speechProbability = alignedVoiceProbability,
            fittingProfile = settings.fittingProfile,
            voiceShaping = settings.mode == ProcessingMode.VOICE_FOCUS,
        )
        processedFrames++

        return FrameProcessingMetrics(
            inputDbFs = amplitudeToDb(rms(input)),
            outputDbFs = amplitudeToDb(rms(output)),
            speechProbability = voice.probability,
            vadRawProbability = voice.rawProbability,
            vadSpeechDetected = voice.speechDetected,
            vadProcessedWindows = voice.processedWindows,
            vadInferenceMs = voice.averageInferenceMs,
            vadModelName = "DEEPFILTERNET3 · ${voice.modelName}",
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
        inference.close()
    }

    private fun rmsDifference(first: FloatArray, second: FloatArray): Float {
        var sum = 0.0
        for (index in first.indices) {
            val difference = first[index] - second[index]
            sum += difference * difference
        }
        return sqrt(sum / first.size).toFloat()
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return sqrt(sum / samples.size).toFloat()
    }

    private fun rms(samples: ShortArray): Float {
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / frameSizeSamples).toFloat()
    }

    private fun amplitudeToDb(amplitude: Float): Float =
        (20f * log10(max(amplitude, 1e-6f))).coerceAtLeast(-120f)

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SIZE = 480
        const val LATENCY_FRAMES = 3
        const val MODEL_ASSET = "models/deepfilternet3_onnx.dfmodel"
        const val DEFAULT_ATTENUATION_LIMIT_DB = 100f
        private const val NATIVE_ERROR_SENTINEL = -150f
        private const val MIN_METRIC_RMS = 1e-5f
    }
}
