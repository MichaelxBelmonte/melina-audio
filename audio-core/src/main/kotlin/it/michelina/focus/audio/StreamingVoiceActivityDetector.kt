package it.michelina.focus.audio

import kotlin.math.max

interface VoiceActivityInference : AutoCloseable {
    fun compute(window: FloatArray): Float
}

/**
 * Platform-neutral streaming Silero/TEN VAD front-end. Runtime adapters only evaluate one 16 kHz
 * model window; resampling, smoothing, hangover and telemetry are identical on every platform.
 */
class StreamingVoiceActivityDetector(
    private val inputSampleRate: Int,
    private val backend: VoiceDetectorBackend = VoiceDetectorBackend.SILERO,
    private val inference: VoiceActivityInference,
) : VoiceActivityDetector {
    private val windowSize = when (backend) {
        VoiceDetectorBackend.SILERO -> SILERO_WINDOW_SIZE
        VoiceDetectorBackend.TEN_VAD -> TEN_WINDOW_SIZE
    }
    private val modelWindow = FloatArray(windowSize)
    private var modelWindowSize = 0
    private val downsampler48k = if (inputSampleRate == 48_000) {
        StreamingFirDecimatorByThree(FactorThreeSampleRateConverter.ANTI_ALIAS_TAPS)
    } else {
        null
    }
    private var previousUpsampleValue = 0f
    private var hasPreviousUpsampleValue = false
    private var rawProbability = 0f
    private var smoothedProbability = 0f
    private var speechDetected = false
    private var hangoverWindows = 0
    private var processedWindows = 0L
    private var averageInferenceMs = 0f
    private var released = false

    init {
        require(inputSampleRate == 8_000 || inputSampleRate == 16_000 || inputSampleRate == 48_000) {
            "The neural VAD does not support $inputSampleRate Hz input"
        }
    }

    override fun process(input: ShortArray): VoiceActivityEstimate {
        check(!released) { "The neural VAD has already been released" }
        when (inputSampleRate) {
            MODEL_SAMPLE_RATE -> for (sample in input) appendModelSample(sample / 32768f)
            48_000 -> for (sample in input) {
                val filtered = checkNotNull(downsampler48k).push(sample / 32768f)
                if (!filtered.isNaN()) appendModelSample(filtered)
            }
            8_000 -> for (sample in input) {
                val value = sample / 32768f
                if (hasPreviousUpsampleValue) {
                    appendModelSample((previousUpsampleValue + value) * 0.5f)
                } else {
                    appendModelSample(value)
                    hasPreviousUpsampleValue = true
                }
                appendModelSample(value)
                previousUpsampleValue = value
            }
        }
        return VoiceActivityEstimate(
            probability = smoothedProbability,
            rawProbability = rawProbability,
            speechDetected = speechDetected,
            processedWindows = processedWindows,
            averageInferenceMs = averageInferenceMs,
            modelName = when (backend) {
                VoiceDetectorBackend.SILERO -> SILERO_MODEL_NAME
                VoiceDetectorBackend.TEN_VAD -> TEN_MODEL_NAME
            },
        )
    }

    private fun appendModelSample(value: Float) {
        modelWindow[modelWindowSize++] = value
        if (modelWindowSize != modelWindow.size) return

        val startedAt = System.nanoTime()
        rawProbability = inference.compute(modelWindow).coerceIn(0f, 1f)
        val inferenceMs = (System.nanoTime() - startedAt) / 1_000_000f
        averageInferenceMs = if (processedWindows == 0L) {
            inferenceMs
        } else {
            0.92f * averageInferenceMs + 0.08f * inferenceMs
        }
        processedWindows++
        modelWindowSize = 0

        val smoothing = if (rawProbability > smoothedProbability) ATTACK else RELEASE
        smoothedProbability = smoothing * rawProbability + (1f - smoothing) * smoothedProbability
        if (rawProbability >= SPEECH_THRESHOLD || smoothedProbability >= SPEECH_THRESHOLD) {
            speechDetected = true
            hangoverWindows = hangoverWindowsFor(windowSize)
        } else if (hangoverWindows > 0) {
            hangoverWindows--
            speechDetected = true
        } else {
            speechDetected = false
        }
        if (speechDetected) smoothedProbability = max(smoothedProbability, ACTIVE_FLOOR)
    }

    override fun close() {
        if (released) return
        released = true
        inference.close()
    }

    companion object {
        const val SILERO_MODEL_ASSET = "models/silero_vad.onnx"
        const val TEN_MODEL_ASSET = "models/ten-vad.onnx"
        const val SILERO_MODEL_NAME = "SILERO VAD"
        const val TEN_MODEL_NAME = "TEN VAD"
        const val MODEL_SAMPLE_RATE = 16_000
        const val SILERO_WINDOW_SIZE = 512
        const val TEN_WINDOW_SIZE = 256
        private const val SPEECH_THRESHOLD = 0.50f
        private const val ATTACK = 0.62f
        private const val RELEASE = 0.18f
        private const val ACTIVE_FLOOR = 0.52f
        private const val HANGOVER_MILLISECONDS = 190

        private fun hangoverWindowsFor(windowSize: Int): Int =
            ((HANGOVER_MILLISECONDS * MODEL_SAMPLE_RATE) /
                (1_000f * windowSize)).toInt().coerceAtLeast(1)
    }
}
