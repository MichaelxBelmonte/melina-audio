package it.michelina.focus.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * RNNoise 0.2, compiled from the official Xiph release and executed natively at 48 kHz.
 * The wet and dry paths share the model's one-hop delay so live A/B comparisons stay aligned.
 */
internal class RnnoiseSpeechEnhancer : RealtimeAudioProcessor {
    private var nativeHandle = RnnoiseBridge.nativeCreate()
    private val nativeFrame = FloatArray(FRAME_SIZE)
    private val delayedDry = FloatArray(FRAME_SIZE)
    private val mixed = FloatArray(FRAME_SIZE)
    private val conditioner = NeuralOutputConditioner(SAMPLE_RATE)
    private var speechProbability = 0f
    private var processedFrames = 0L
    private var lastMode = ProcessingMode.VOICE_FOCUS

    override val frameSizeSamples: Int = RnnoiseBridge.nativeFrameSize()
    override val algorithmLatencySamples: Int = FRAME_SIZE

    init {
        check(nativeHandle != 0L) { "RNNoise: native initialization failed" }
        check(frameSizeSamples == FRAME_SIZE) {
            "RNNoise: frame $frameSizeSamples instead of $FRAME_SIZE samples"
        }
    }

    override fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics {
        check(nativeHandle != 0L) { "RNNoise has already been released" }
        require(input.size >= FRAME_SIZE && output.size >= FRAME_SIZE)

        val rawProbability = RnnoiseBridge.nativeProcess(nativeHandle, input, nativeFrame)
        check(rawProbability >= 0f) { "RNNoise: native inference failed" }
        speechProbability = if (processedFrames == 0L) {
            rawProbability
        } else {
            0.78f * speechProbability + 0.22f * rawProbability
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
        for (index in 0 until FRAME_SIZE) {
            mixed[index] = delayedDry[index] + (nativeFrame[index] - delayedDry[index]) * wet
        }

        val dryRms = rms(delayedDry)
        val mixedRms = rms(mixed)
        val changedRms = rmsDifference(mixed, delayedDry)
        val conditioning = conditioner.process(
            input = mixed,
            output = output,
            gainDb = settings.gainDb,
            clarity = settings.clarity,
            quietSpeechBoostDb = settings.quietSpeechBoostDb,
            speechProbability = speechProbability,
            fittingProfile = settings.fittingProfile,
            voiceShaping = settings.mode == ProcessingMode.VOICE_FOCUS,
        )
        for (index in 0 until FRAME_SIZE) delayedDry[index] = input[index] / 32768f
        processedFrames++

        return FrameProcessingMetrics(
            inputDbFs = amplitudeToDb(rms(input)),
            outputDbFs = amplitudeToDb(rms(output)),
            speechProbability = speechProbability,
            vadRawProbability = rawProbability,
            vadSpeechDetected = speechProbability >= SPEECH_THRESHOLD,
            vadProcessedWindows = processedFrames,
            vadModelName = "RNNOISE 0.2 · VAD INTERNO",
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
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        RnnoiseBridge.nativeDestroy(handle)
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
        return sqrt(sum / FRAME_SIZE).toFloat()
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

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SIZE = 480
        private const val SPEECH_THRESHOLD = 0.45f
        private const val MIN_METRIC_RMS = 1e-5f
    }
}

internal object RnnoiseBridge {
    init {
        System.loadLibrary("michelina_audio")
    }

    external fun nativeCreate(): Long
    external fun nativeFrameSize(): Int
    external fun nativeProcess(handle: Long, input: ShortArray, output: FloatArray): Float
    external fun nativeDestroy(handle: Long)
}
