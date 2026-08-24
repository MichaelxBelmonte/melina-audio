package it.michelina.focus.audio

/** Android JNI adapter around the shared RNNoise processor. */
internal class RnnoiseSpeechEnhancer : RealtimeAudioProcessor {
    private val delegate = RnnoiseAudioProcessor(AndroidRnnoiseInference())

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
        const val FRAME_SIZE = RnnoiseAudioProcessor.FRAME_SIZE
    }
}

private class AndroidRnnoiseInference : RnnoiseInference {
    override val frameSizeSamples: Int = RnnoiseBridge.nativeFrameSize()
    private var nativeHandle = RnnoiseBridge.nativeCreate()

    init {
        check(nativeHandle != 0L) { "RNNoise: native initialization failed" }
        check(frameSizeSamples == RnnoiseAudioProcessor.FRAME_SIZE) {
            "RNNoise: frame $frameSizeSamples instead of ${RnnoiseAudioProcessor.FRAME_SIZE} samples"
        }
    }

    override fun process(input: ShortArray, output: FloatArray): Float {
        check(nativeHandle != 0L) { "RNNoise has already been released" }
        return RnnoiseBridge.nativeProcess(nativeHandle, input, output)
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        RnnoiseBridge.nativeDestroy(handle)
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
