package it.michelina.focus.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig

/** Android adapter around the platform-neutral streaming neural processor. */
internal class NeuralSpeechEnhancer(
    context: Context,
    val backend: ProcessorBackend,
    voiceDetectorBackend: VoiceDetectorBackend,
) : RealtimeAudioProcessor {
    private val spec = neuralModelSpec(backend)
    private val denoiser = AndroidSherpaStreamingDenoiser(context, spec)
    private val delegate = try {
        StreamingNeuralSpeechEnhancer(
            backend = backend,
            denoiser = denoiser,
            voiceDetector = NeuralVoiceDetector(context, spec.sampleRate, voiceDetectorBackend),
        )
    } catch (error: Throwable) {
        denoiser.close()
        throw error
    }

    override val frameSizeSamples: Int
        get() = delegate.frameSizeSamples

    override val algorithmLatencySamples: Int
        get() = delegate.algorithmLatencySamples

    override fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics = delegate.process(input, output, settings)

    override fun close() = delegate.close()
}

private class AndroidSherpaStreamingDenoiser(
    context: Context,
    private val spec: NeuralModelSpec,
) : StreamingSpeechDenoiser {
    private val denoiser: OnlineSpeechDenoiser

    init {
        val modelConfig = OfflineSpeechDenoiserModelConfig().apply {
            when (spec.backend) {
                ProcessorBackend.GTCRN_FAST -> {
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig().apply { model = spec.assetPath }
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
                else -> error("Invalid sherpa-onnx backend: ${spec.backend}")
            }
            numThreads = when (spec.backend) {
                ProcessorBackend.DPDFNET4_STRONG,
                ProcessorBackend.DPDFNET8_SPEECH -> 2
                else -> 1
            }
            debug = false
            provider = "cpu"
        }
        denoiser = OnlineSpeechDenoiser(
            context.applicationContext.assets,
            OnlineSpeechDenoiserConfig().apply { model = modelConfig },
        )
    }

    override val sampleRate: Int
        get() = denoiser.sampleRate

    override val frameShiftSamples: Int
        get() = denoiser.frameShiftInSamples

    override fun run(input: FloatArray): FloatArray = denoiser.run(input, sampleRate).samples

    override fun close() = denoiser.release()
}
