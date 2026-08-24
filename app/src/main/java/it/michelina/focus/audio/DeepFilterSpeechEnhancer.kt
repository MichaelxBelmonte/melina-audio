package it.michelina.focus.audio

import android.content.Context
import java.io.File

/** Android libDF adapter around the shared DeepFilterNet3 streaming processor. */
internal class DeepFilterSpeechEnhancer(
    context: Context,
    voiceDetectorBackend: VoiceDetectorBackend,
) : RealtimeAudioProcessor {
    private val delegate = DeepFilterAudioProcessor(
        inference = AndroidDeepFilterInference(materializeModel(context)),
        voiceDetector = NeuralVoiceDetector(
            context,
            DeepFilterAudioProcessor.SAMPLE_RATE,
            voiceDetectorBackend,
        ),
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
        const val SAMPLE_RATE = DeepFilterAudioProcessor.SAMPLE_RATE
        const val FRAME_SIZE = DeepFilterAudioProcessor.FRAME_SIZE
        const val LATENCY_FRAMES = DeepFilterAudioProcessor.LATENCY_FRAMES
        private const val MODEL_FILE = "deepfilternet3-c94d91f7.dfmodel"

        private fun materializeModel(context: Context): File {
            val directory = File(context.noBackupFilesDir, "models")
            check(directory.exists() || directory.mkdirs()) {
                "Unable to create the DeepFilterNet model directory"
            }
            val target = File(directory, MODEL_FILE)
            if (!target.isFile) {
                val temporary = File(directory, "$MODEL_FILE.tmp")
                context.assets.open(DeepFilterAudioProcessor.MODEL_ASSET).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.renameTo(target)) { "Unable to install the DeepFilterNet3 model" }
            }
            return target
        }
    }
}

private class AndroidDeepFilterInference(modelFile: File) : DeepFilterInference {
    private var nativeHandle = DeepFilterBridge.nativeCreate(
        modelFile.absolutePath,
        DeepFilterAudioProcessor.DEFAULT_ATTENUATION_LIMIT_DB,
    )

    override val frameSizeSamples: Int

    init {
        check(nativeHandle != 0L) { "DeepFilterNet3: libDF initialization failed" }
        frameSizeSamples = DeepFilterBridge.nativeFrameSize(nativeHandle)
    }

    override fun process(input: ShortArray, output: FloatArray): Float {
        check(nativeHandle != 0L) { "DeepFilterNet3 has already been released" }
        return DeepFilterBridge.nativeProcess(nativeHandle, input, output)
    }

    override fun setParameters(attenuationLimitDb: Float, postFilterBeta: Float) {
        check(nativeHandle != 0L) { "DeepFilterNet3 has already been released" }
        DeepFilterBridge.nativeSetParameters(nativeHandle, attenuationLimitDb, postFilterBeta)
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        DeepFilterBridge.nativeDestroy(handle)
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
