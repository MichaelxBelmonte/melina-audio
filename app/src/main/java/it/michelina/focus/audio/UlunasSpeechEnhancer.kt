package it.michelina.focus.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Causal streaming port of the official UL-UNAS ONNX graph.
 *
 * The repository provides a stateful spectrum-to-spectrum model. This class supplies the missing
 * mobile audio frontend: 512-point Hann STFT, recurrent caches, inverse STFT and overlap-add.
 */
internal class UlunasSpeechEnhancer(
    context: Context,
    voiceDetectorBackend: VoiceDetectorBackend,
) : RealtimeAudioProcessor {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
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
    private var convCache = FloatArray(CONV_CACHE_SIZE)
    private var tfaCache = FloatArray(TFA_CACHE_SIZE)
    private var interCache = FloatArray(INTER_CACHE_SIZE)
    private val conditioner = NeuralOutputConditioner(SAMPLE_RATE)
    private val voiceDetector = NeuralVoiceDetector(context, SAMPLE_RATE, voiceDetectorBackend)
    private var processedFrames = 0L
    private var lastMode = ProcessingMode.VOICE_FOCUS
    private var released = false

    override val frameSizeSamples: Int = HOP_SIZE
    override val algorithmLatencySamples: Int = HOP_SIZE

    init {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = try {
            environment.createSession(model, options)
        } finally {
            options.close()
        }
        check(session.inputNames == EXPECTED_INPUTS) {
            "UL-UNAS: unexpected ONNX inputs ${session.inputNames}"
        }
        check(session.outputNames == EXPECTED_OUTPUTS) {
            "UL-UNAS: unexpected ONNX outputs ${session.outputNames}"
        }
    }

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

        runModelFrame()

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

    private fun runModelFrame() {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(inputSpectrum), MIX_SHAPE).use { mix ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(convCache), CONV_CACHE_SHAPE).use { conv ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(tfaCache), TFA_CACHE_SHAPE).use { tfa ->
                    OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(interCache),
                        INTER_CACHE_SHAPE,
                    ).use { inter ->
                        session.run(
                            mapOf(
                                "mix" to mix,
                                "conv_cache" to conv,
                                "tfa_cache" to tfa,
                                "inter_cache" to inter,
                            ),
                        ).use { result ->
                            copyTensor(result[0] as OnnxTensor, enhancedSpectrum)
                            copyTensor(result[1] as OnnxTensor, convCache)
                            copyTensor(result[2] as OnnxTensor, tfaCache)
                            copyTensor(result[3] as OnnxTensor, interCache)
                        }
                    }
                }
            }
        }
    }

    private fun copyTensor(tensor: OnnxTensor, destination: FloatArray) {
        val buffer = tensor.floatBuffer
        buffer.rewind()
        buffer.get(destination)
    }

    override fun close() {
        if (released) return
        released = true
        voiceDetector.close()
        session.close()
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
        private const val FFT_SIZE = 512
        private const val BIN_COUNT = FFT_SIZE / 2 + 1
        private const val SPECTRUM_VALUES = BIN_COUNT * 2
        private const val CONV_CACHE_SIZE = 5_358
        private const val TFA_CACHE_SIZE = 402
        private const val INTER_CACHE_SIZE = 1_056
        private const val MODEL_ASSET = "models/ulunas_stream_simple.onnx"
        private const val MIN_METRIC_RMS = 1e-5f
        private val MIX_SHAPE = longArrayOf(1, BIN_COUNT.toLong(), 1, 2)
        private val CONV_CACHE_SHAPE = longArrayOf(1, CONV_CACHE_SIZE.toLong())
        private val TFA_CACHE_SHAPE = longArrayOf(1, TFA_CACHE_SIZE.toLong())
        private val INTER_CACHE_SHAPE = longArrayOf(1, INTER_CACHE_SIZE.toLong())
        private val EXPECTED_INPUTS = setOf("mix", "conv_cache", "tfa_cache", "inter_cache")
        private val EXPECTED_OUTPUTS =
            setOf("enh", "conv_cache_out", "tfa_cache_out", "inter_cache_out")
    }
}
