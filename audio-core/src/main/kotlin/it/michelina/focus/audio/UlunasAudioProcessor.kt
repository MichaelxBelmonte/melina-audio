package it.michelina.focus.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Evaluates one stateful UL-UNAS spectrum frame and updates the recurrent caches in place. */
interface UlunasInference : AutoCloseable {
    fun process(
        inputSpectrum: FloatArray,
        convCache: FloatArray,
        tfaCache: FloatArray,
        interCache: FloatArray,
        enhancedSpectrum: FloatArray,
    )
}

/**
 * Platform-neutral causal UL-UNAS frontend: STFT, recurrent state, overlap-add, wet/dry alignment,
 * hearing-oriented conditioning, and telemetry. Platform adapters only provide ONNX inference.
 */
class UlunasAudioProcessor(
    private val inference: UlunasInference,
    private val voiceDetector: VoiceActivityDetector,
) : RealtimeAudioProcessor {
    private val fft = Radix2Fft(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / FFT_SIZE)).toFloat()
    }
    private val overlapNormalization = FloatArray(HOP_SIZE) { index ->
        val first = window[index]
        val second = window[index + HOP_SIZE]
        max(first * first + second * second, 1e-6f)
    }
    private val previousInput = FloatArray(HOP_SIZE)
    private val delayedDry = FloatArray(HOP_SIZE)
    private val real = FloatArray(FFT_SIZE)
    private val imaginary = FloatArray(FFT_SIZE)
    private val inputSpectrum = FloatArray(SPECTRUM_VALUES)
    private val enhancedSpectrum = FloatArray(SPECTRUM_VALUES)
    private val overlap = FloatArray(HOP_SIZE)
    private val enhancedFrame = FloatArray(HOP_SIZE)
    private val mixedFrame = FloatArray(HOP_SIZE)
    private val convCache = FloatArray(CONV_CACHE_SIZE)
    private val tfaCache = FloatArray(TFA_CACHE_SIZE)
    private val interCache = FloatArray(INTER_CACHE_SIZE)
    private val conditioner = NeuralOutputConditioner(SAMPLE_RATE)
    private var processedFrames = 0L
    private var lastMode = ProcessingMode.VOICE_FOCUS
    private var released = false

    override val frameSizeSamples: Int = HOP_SIZE
    override val algorithmLatencySamples: Int = HOP_SIZE

    override fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics {
        check(!released) { "UL-UNAS has already been released" }
        require(input.size >= HOP_SIZE && output.size >= HOP_SIZE)

        for (index in 0 until HOP_SIZE) {
            real[index] = previousInput[index] * window[index]
            val current = input[index] / 32768f
            real[index + HOP_SIZE] = current * window[index + HOP_SIZE]
            previousInput[index] = current
            imaginary[index] = 0f
            imaginary[index + HOP_SIZE] = 0f
        }
        fft.forward(real, imaginary)
        for (bin in 0 until BIN_COUNT) {
            val offset = bin * 2
            inputSpectrum[offset] = real[bin]
            inputSpectrum[offset + 1] = imaginary[bin]
        }

        inference.process(inputSpectrum, convCache, tfaCache, interCache, enhancedSpectrum)

        for (bin in 0 until BIN_COUNT) {
            val offset = bin * 2
            real[bin] = enhancedSpectrum[offset]
            imaginary[bin] = enhancedSpectrum[offset + 1]
            if (bin != 0 && bin != FFT_SIZE / 2) {
                real[FFT_SIZE - bin] = real[bin]
                imaginary[FFT_SIZE - bin] = -imaginary[bin]
            }
        }
        fft.inverse(real, imaginary)
        for (index in 0 until HOP_SIZE) {
            enhancedFrame[index] =
                (overlap[index] + real[index] * window[index]) / overlapNormalization[index]
            overlap[index] = real[index + HOP_SIZE] * window[index + HOP_SIZE]
        }

        val voice = voiceDetector.process(input)
        if (settings.mode != lastMode) {
            conditioner.reset()
            lastMode = settings.mode
        }
        val wet = if (settings.mode == ProcessingMode.VOICE_FOCUS) {
            settings.denoiseStrength.coerceIn(0f, 1f)
        } else {
            0f
        }
        for (index in 0 until HOP_SIZE) {
            mixedFrame[index] = delayedDry[index] + (enhancedFrame[index] - delayedDry[index]) * wet
        }

        val dryRms = rms(delayedDry)
        val mixedRms = rms(mixedFrame)
        val changedRms = rmsDifference(mixedFrame, delayedDry)
        val conditioning = conditioner.process(
            input = mixedFrame,
            output = output,
            gainDb = settings.gainDb,
            clarity = settings.clarity,
            quietSpeechBoostDb = settings.quietSpeechBoostDb,
            speechProbability = voice.probability,
            fittingProfile = settings.fittingProfile,
            voiceShaping = settings.mode == ProcessingMode.VOICE_FOCUS,
        )
        for (index in 0 until HOP_SIZE) delayedDry[index] = input[index] / 32768f
        processedFrames++

        return FrameProcessingMetrics(
            inputDbFs = amplitudeToDb(rms(input)),
            outputDbFs = amplitudeToDb(rms(output)),
            speechProbability = voice.probability,
            vadRawProbability = voice.rawProbability,
            vadSpeechDetected = voice.speechDetected,
            vadProcessedWindows = voice.processedWindows,
            vadInferenceMs = voice.averageInferenceMs,
            vadModelName = "UL-UNAS STREAM · ${voice.modelName}",
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
        return sqrt(sum / HOP_SIZE).toFloat()
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
        const val SAMPLE_RATE = 16_000
        const val HOP_SIZE = 256
        const val BIN_COUNT = 257
        const val SPECTRUM_VALUES = BIN_COUNT * 2
        const val CONV_CACHE_SIZE = 5_358
        const val TFA_CACHE_SIZE = 402
        const val INTER_CACHE_SIZE = 1_056
        const val MODEL_ASSET = "models/ulunas_stream_simple.onnx"
        private const val FFT_SIZE = 512
        private const val MIN_METRIC_RMS = 1e-5f
    }
}
