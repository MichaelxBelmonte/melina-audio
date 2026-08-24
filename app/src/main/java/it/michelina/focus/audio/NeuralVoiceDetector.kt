package it.michelina.focus.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Android sherpa-onnx adapter around the shared streaming VAD front-end. */
internal class NeuralVoiceDetector(
    context: Context,
    inputSampleRate: Int,
    backend: VoiceDetectorBackend = VoiceDetectorBackend.SILERO,
) : VoiceActivityDetector {
    private val inference = AndroidVadInference(context, backend)
    private val delegate = try {
        StreamingVoiceActivityDetector(inputSampleRate, backend, inference)
    } catch (error: Throwable) {
        inference.close()
        throw error
    }

    override fun process(input: ShortArray): VoiceActivityEstimate = delegate.process(input)

    override fun close() = delegate.close()

    companion object {
        const val SILERO_MODEL_ASSET = StreamingVoiceActivityDetector.SILERO_MODEL_ASSET
        const val TEN_MODEL_ASSET = StreamingVoiceActivityDetector.TEN_MODEL_ASSET
        const val SILERO_MODEL_NAME = StreamingVoiceActivityDetector.SILERO_MODEL_NAME
        const val TEN_MODEL_NAME = StreamingVoiceActivityDetector.TEN_MODEL_NAME
        const val MODEL_SAMPLE_RATE = StreamingVoiceActivityDetector.MODEL_SAMPLE_RATE
        const val SILERO_WINDOW_SIZE = StreamingVoiceActivityDetector.SILERO_WINDOW_SIZE
        const val TEN_WINDOW_SIZE = StreamingVoiceActivityDetector.TEN_WINDOW_SIZE
    }
}

private class AndroidVadInference(
    context: Context,
    backend: VoiceDetectorBackend,
) : VoiceActivityInference {
    private val vad = Vad(
        context.applicationContext.assets,
        VadModelConfig().apply {
            when (backend) {
                VoiceDetectorBackend.SILERO -> {
                    sileroVadModelConfig = SileroVadModelConfig().apply {
                        model = StreamingVoiceActivityDetector.SILERO_MODEL_ASSET
                        threshold = SPEECH_THRESHOLD
                        minSilenceDuration = 0.10f
                        minSpeechDuration = 0.05f
                        windowSize = StreamingVoiceActivityDetector.SILERO_WINDOW_SIZE
                        maxSpeechDuration = 60f
                    }
                }
                VoiceDetectorBackend.TEN_VAD -> {
                    tenVadModelConfig = TenVadModelConfig().apply {
                        model = StreamingVoiceActivityDetector.TEN_MODEL_ASSET
                        threshold = SPEECH_THRESHOLD
                        minSilenceDuration = 0.10f
                        minSpeechDuration = 0.05f
                        windowSize = StreamingVoiceActivityDetector.TEN_WINDOW_SIZE
                        maxSpeechDuration = 60f
                    }
                }
            }
            sampleRate = StreamingVoiceActivityDetector.MODEL_SAMPLE_RATE
            numThreads = 1
            provider = "cpu"
            debug = false
        },
    )

    override fun compute(window: FloatArray): Float = vad.compute(window)

    override fun close() = vad.release()

    companion object {
        private const val SPEECH_THRESHOLD = 0.50f
    }
}
