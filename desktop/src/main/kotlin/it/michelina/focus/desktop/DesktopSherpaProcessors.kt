package it.michelina.focus.desktop

import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import it.michelina.focus.audio.NativeRateAudioProcessor
import it.michelina.focus.audio.DeepFilterAudioProcessor
import it.michelina.focus.audio.NeuralModelSpec
import it.michelina.focus.audio.ProcessorBackend
import it.michelina.focus.audio.RealtimeAudioProcessor
import it.michelina.focus.audio.RnnoiseAudioProcessor
import it.michelina.focus.audio.SpectralSpeechEnhancer
import it.michelina.focus.audio.StreamingNeuralSpeechEnhancer
import it.michelina.focus.audio.StreamingSpeechDenoiser
import it.michelina.focus.audio.StreamingVoiceActivityDetector
import it.michelina.focus.audio.UlunasAudioProcessor
import it.michelina.focus.audio.VoiceActivityInference
import it.michelina.focus.audio.VoiceDetectorBackend
import it.michelina.focus.audio.neuralModelSpec

internal object DesktopProcessorFactory {
    val supportedBackends: List<ProcessorBackend>
        get() = buildList {
            add(ProcessorBackend.CLASSIC_DSP)
            add(ProcessorBackend.RNNOISE_NATIVE)
            add(ProcessorBackend.ULUNAS_STREAM)
            if (DesktopNativeLibrary.hasDeepFilter()) add(ProcessorBackend.DEEPFILTER3_HQ)
            add(ProcessorBackend.GTCRN_FAST)
            add(ProcessorBackend.DPDFNET2_BALANCED)
            add(ProcessorBackend.DPDFNET4_STRONG)
            add(ProcessorBackend.DPDFNET8_SPEECH)
            add(ProcessorBackend.DPDFNET_HQ)
        }

    fun create(
        backend: ProcessorBackend,
        vadBackend: VoiceDetectorBackend = VoiceDetectorBackend.SILERO,
    ): RealtimeAudioProcessor = when (backend) {
        ProcessorBackend.CLASSIC_DSP -> SpectralSpeechEnhancer(
            sampleRate = DesktopAudioDevices.SAMPLE_RATE,
            voiceDetector = desktopVoiceDetector(DesktopAudioDevices.SAMPLE_RATE, vadBackend),
        )
        ProcessorBackend.RNNOISE_NATIVE -> RnnoiseAudioProcessor(DesktopRnnoiseInference())
        ProcessorBackend.ULUNAS_STREAM -> {
            val inference = DesktopUlunasInference()
            val model = try {
                UlunasAudioProcessor(
                    inference,
                    desktopVoiceDetector(UlunasAudioProcessor.SAMPLE_RATE, vadBackend),
                )
            } catch (error: Throwable) {
                inference.close()
                throw error
            }
            NativeRateAudioProcessor(model)
        }
        ProcessorBackend.DEEPFILTER3_HQ -> {
            val inference = DesktopDeepFilterInference()
            try {
                DeepFilterAudioProcessor(
                    inference,
                    desktopVoiceDetector(DeepFilterAudioProcessor.SAMPLE_RATE, vadBackend),
                )
            } catch (error: Throwable) {
                inference.close()
                throw error
            }
        }
        ProcessorBackend.GTCRN_FAST,
        ProcessorBackend.DPDFNET2_BALANCED,
        ProcessorBackend.DPDFNET4_STRONG,
        ProcessorBackend.DPDFNET8_SPEECH,
        ProcessorBackend.DPDFNET_HQ -> {
            val spec = neuralModelSpec(backend)
            val denoiser = DesktopSherpaStreamingDenoiser(spec)
            val model = try {
                StreamingNeuralSpeechEnhancer(
                    backend,
                    denoiser,
                    desktopVoiceDetector(spec.sampleRate, vadBackend),
                )
            } catch (error: Throwable) {
                denoiser.close()
                throw error
            }
            if (spec.sampleRate == DesktopAudioDevices.SAMPLE_RATE) {
                model
            } else {
                NativeRateAudioProcessor(model)
            }
        }
    }

    private fun desktopVoiceDetector(
        sampleRate: Int,
        backend: VoiceDetectorBackend,
    ): StreamingVoiceActivityDetector {
        val inference = DesktopSherpaVadInference(backend)
        return try {
            StreamingVoiceActivityDetector(sampleRate, backend, inference)
        } catch (error: Throwable) {
            inference.close()
            throw error
        }
    }
}

private class DesktopSherpaStreamingDenoiser(
    private val spec: NeuralModelSpec,
) : StreamingSpeechDenoiser {
    private val denoiser: OnlineSpeechDenoiser

    init {
        val modelPath = DesktopModelRepository.materialize(spec.assetPath).toString()
        val modelBuilder = OfflineSpeechDenoiserModelConfig.builder()
            .setNumThreads(
                when (spec.backend) {
                    ProcessorBackend.DPDFNET4_STRONG,
                    ProcessorBackend.DPDFNET8_SPEECH -> 2
                    else -> 1
                },
            )
            .setDebug(false)
            .setProvider("cpu")
        when (spec.backend) {
            ProcessorBackend.GTCRN_FAST -> modelBuilder.setGtcrn(
                OfflineSpeechDenoiserGtcrnModelConfig.builder()
                    .setModel(modelPath)
                    .build(),
            )
            ProcessorBackend.DPDFNET2_BALANCED,
            ProcessorBackend.DPDFNET4_STRONG,
            ProcessorBackend.DPDFNET8_SPEECH,
            ProcessorBackend.DPDFNET_HQ -> modelBuilder.setDpdfnet(
                OfflineSpeechDenoiserDpdfNetModelConfig.builder()
                    .setModel(modelPath)
                    .build(),
            )
            else -> error("Invalid sherpa-onnx backend: ${spec.backend}")
        }
        denoiser = OnlineSpeechDenoiser(
            OnlineSpeechDenoiserConfig.builder().setModel(modelBuilder.build()).build(),
        )
    }

    override val sampleRate: Int
        get() = denoiser.sampleRate

    override val frameShiftSamples: Int
        get() = denoiser.frameShiftInSamples

    override fun run(input: FloatArray): FloatArray = denoiser.run(input, sampleRate).samples

    override fun close() = denoiser.release()
}

private class DesktopSherpaVadInference(
    backend: VoiceDetectorBackend,
) : VoiceActivityInference {
    private val vad: Vad

    init {
        val configBuilder = VadModelConfig.builder()
            .setSampleRate(StreamingVoiceActivityDetector.MODEL_SAMPLE_RATE)
            .setNumThreads(1)
            .setProvider("cpu")
            .setDebug(false)
        when (backend) {
            VoiceDetectorBackend.SILERO -> configBuilder.setSileroVadModelConfig(
                SileroVadModelConfig.builder()
                    .setModel(
                        DesktopModelRepository.materialize(
                            StreamingVoiceActivityDetector.SILERO_MODEL_ASSET,
                        ).toString(),
                    )
                    .setThreshold(SPEECH_THRESHOLD)
                    .setMinSilenceDuration(0.10f)
                    .setMinSpeechDuration(0.05f)
                    .setWindowSize(StreamingVoiceActivityDetector.SILERO_WINDOW_SIZE)
                    .setMaxSpeechDuration(60f)
                    .build(),
            )
            VoiceDetectorBackend.TEN_VAD -> configBuilder.setTenVadModelConfig(
                TenVadModelConfig.builder()
                    .setModel(
                        DesktopModelRepository.materialize(
                            StreamingVoiceActivityDetector.TEN_MODEL_ASSET,
                        ).toString(),
                    )
                    .setThreshold(SPEECH_THRESHOLD)
                    .setMinSilenceDuration(0.10f)
                    .setMinSpeechDuration(0.05f)
                    .setWindowSize(StreamingVoiceActivityDetector.TEN_WINDOW_SIZE)
                    .setMaxSpeechDuration(60f)
                    .build(),
            )
        }
        vad = Vad(configBuilder.build())
    }

    override fun compute(window: FloatArray): Float = vad.compute(window)

    override fun close() = vad.release()

    companion object {
        private const val SPEECH_THRESHOLD = 0.50f
    }
}

internal fun ProcessorBackend.displayName(): String = when (this) {
    ProcessorBackend.CLASSIC_DSP -> "Classic DSP"
    ProcessorBackend.GTCRN_FAST -> "GTCRN Fast"
    ProcessorBackend.DPDFNET2_BALANCED -> "DPDFNet2 Balanced"
    ProcessorBackend.DPDFNET4_STRONG -> "DPDFNet4 Strong"
    ProcessorBackend.DPDFNET8_SPEECH -> "DPDFNet8 Speech"
    ProcessorBackend.DPDFNET_HQ -> "DPDFNet2 HQ"
    ProcessorBackend.RNNOISE_NATIVE -> "RNNoise Native 0.2"
    ProcessorBackend.ULUNAS_STREAM -> "UL-UNAS Stream"
    ProcessorBackend.DEEPFILTER3_HQ -> "DeepFilterNet3 HQ"
}
