package it.michelina.focus.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/** Android ONNX Runtime adapter around the shared UL-UNAS streaming processor. */
internal class UlunasSpeechEnhancer(
    context: Context,
    voiceDetectorBackend: VoiceDetectorBackend,
) : RealtimeAudioProcessor {
    private val delegate = UlunasAudioProcessor(
        inference = AndroidUlunasInference(context),
        voiceDetector = NeuralVoiceDetector(context, UlunasAudioProcessor.SAMPLE_RATE, voiceDetectorBackend),
    )

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

    companion object {
        const val SAMPLE_RATE = UlunasAudioProcessor.SAMPLE_RATE
        const val HOP_SIZE = UlunasAudioProcessor.HOP_SIZE
    }
}

private class AndroidUlunasInference(context: Context) : UlunasInference {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val model = context.assets.open(UlunasAudioProcessor.MODEL_ASSET).use { it.readBytes() }
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
        inputSpectrum: FloatArray,
        convCache: FloatArray,
        tfaCache: FloatArray,
        interCache: FloatArray,
        enhancedSpectrum: FloatArray,
    ) {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(inputSpectrum), MIX_SHAPE).use { mix ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(convCache), CONV_CACHE_SHAPE).use { conv ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(tfaCache), TFA_CACHE_SHAPE).use { tfa ->
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(interCache), INTER_CACHE_SHAPE).use { inter ->
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

    override fun close() = session.close()

    companion object {
        private val MIX_SHAPE = longArrayOf(1, UlunasAudioProcessor.BIN_COUNT.toLong(), 1, 2)
        private val CONV_CACHE_SHAPE = longArrayOf(1, UlunasAudioProcessor.CONV_CACHE_SIZE.toLong())
        private val TFA_CACHE_SHAPE = longArrayOf(1, UlunasAudioProcessor.TFA_CACHE_SIZE.toLong())
        private val INTER_CACHE_SHAPE = longArrayOf(1, UlunasAudioProcessor.INTER_CACHE_SIZE.toLong())
        private val EXPECTED_INPUTS = setOf("mix", "conv_cache", "tfa_cache", "inter_cache")
        private val EXPECTED_OUTPUTS =
            setOf("enh", "conv_cache_out", "tfa_cache_out", "inter_cache_out")
    }
}
