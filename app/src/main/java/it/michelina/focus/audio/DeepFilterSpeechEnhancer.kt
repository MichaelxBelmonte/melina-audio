package it.michelina.focus.audio

import android.content.Context
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Official DeepFilterNet3 C API: 48 kHz STFT, Tract DNN, deep filtering and inverse STFT. */
internal class DeepFilterSpeechEnhancer(
    context: Context,
    voiceDetectorBackend: VoiceDetectorBackend,
) : RealtimeAudioProcessor {
    private val modelFile = materializeModel(context)
    private var nativeHandle = DeepFilterBridge.nativeCreate(
        modelFile.absolutePath,
        DEFAULT_ATTENUATION_LIMIT_DB,
    )
    private val enhancedFrame: FloatArray
    private val delayedDry: FloatArray
    private val alignedDry: FloatArray
    private val mixedFrame: FloatArray
    private val delayedVad = FloatArray(LATENCY_FRAMES)
    private var delayPosition = 0
    private var vadDelayPosition = 0
    private val conditioner = NeuralOutputConditioner(SAMPLE_RATE)
    private val voiceDetector = NeuralVoiceDetector(context, SAMPLE_RATE, voiceDetectorBackend)
    private var processedFrames = 0L
    private var lastMode = ProcessingMode.VOICE_FOCUS

    override val frameSizeSamples: Int
    override val algorithmLatencySamples: Int

    init {
        check(nativeHandle != 0L) { "DeepFilterNet3: libDF initialization failed" }
        frameSizeSamples = DeepFilterBridge.nativeFrameSize(nativeHandle)
        check(frameSizeSamples == FRAME_SIZE) {
            "DeepFilterNet3: frame $frameSizeSamples instead of $FRAME_SIZE samples"
        }
        algorithmLatencySamples = frameSizeSamples * LATENCY_FRAMES
        enhancedFrame = FloatArray(frameSizeSamples)
        delayedDry = FloatArray(algorithmLatencySamples)
        alignedDry = FloatArray(frameSizeSamples)
        mixedFrame = FloatArray(frameSizeSamples)
        DeepFilterBridge.nativeSetParameters(nativeHandle, DEFAULT_ATTENUATION_LIMIT_DB, 0f)
    }

    override fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics {
        check(nativeHandle != 0L) { "DeepFilterNet3 has already been released" }
        require(input.size >= frameSizeSamples && output.size >= frameSizeSamples)

        val localSnrDb = DeepFilterBridge.nativeProcess(nativeHandle, input, enhancedFrame)
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

    private fun rmsDifference(first: FloatArray, second: FloatArray): Float {
        var sum = 0.0
        for (index in first.indices) {
            val difference = first[index] - second[index]
            sum += difference * difference
        }
        return sqrt(sum / first.size).toFloat()
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        voiceDetector.close()
        DeepFilterBridge.nativeDestroy(handle)
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
        private const val MODEL_ASSET = "models/deepfilternet3_onnx.dfmodel"
        private const val MODEL_FILE = "deepfilternet3-c94d91f7.dfmodel"
        private const val DEFAULT_ATTENUATION_LIMIT_DB = 100f
        private const val NATIVE_ERROR_SENTINEL = -150f
        private const val MIN_METRIC_RMS = 1e-5f

        private fun materializeModel(context: Context): File {
            val directory = File(context.noBackupFilesDir, "models")
            check(directory.exists() || directory.mkdirs()) {
                "Unable to create the DeepFilterNet model directory"
            }
            val target = File(directory, MODEL_FILE)
            if (!target.isFile) {
                val temporary = File(directory, "$MODEL_FILE.tmp")
                context.assets.open(MODEL_ASSET).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.renameTo(target)) { "Unable to install the DeepFilterNet3 model" }
            }
            return target
        }
    }
}

internal object DeepFilterBridge {
    init {
        System.loadLibrary("michelina_audio")
    }

    external fun nativeCreate(modelPath: String, attenuationLimitDb: Float): Long
    external fun nativeFrameSize(handle: Long): Int
    external fun nativeProcess(handle: Long, input: ShortArray, output: FloatArray): Float
    external fun nativeSetParameters(handle: Long, attenuationLimitDb: Float, postFilterBeta: Float)
    external fun nativeDestroy(handle: Long)
}
