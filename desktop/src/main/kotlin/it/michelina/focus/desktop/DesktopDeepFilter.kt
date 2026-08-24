package it.michelina.focus.desktop

import it.michelina.focus.audio.DeepFilterAudioProcessor
import it.michelina.focus.audio.DeepFilterInference

/** Dynamic adapter for the official libDF C API compiled for the current desktop target. */
internal class DesktopDeepFilterInference : DeepFilterInference {
    private var nativeHandle: Long
    override val frameSizeSamples: Int

    init {
        DesktopNativeLibrary.load()
        val libraryPath = DesktopNativeLibrary.deepFilterPath()
        val modelPath = DesktopModelRepository.materialize(DeepFilterAudioProcessor.MODEL_ASSET)
        nativeHandle = DesktopDeepFilterBridge.nativeCreate(
            libraryPath.toAbsolutePath().toString(),
            modelPath.toAbsolutePath().toString(),
            DeepFilterAudioProcessor.DEFAULT_ATTENUATION_LIMIT_DB,
        )
        check(nativeHandle != 0L) { "DeepFilterNet3: native desktop initialization failed" }
        frameSizeSamples = DesktopDeepFilterBridge.nativeFrameSize(nativeHandle)
    }

    override fun process(input: ShortArray, output: FloatArray): Float {
        check(nativeHandle != 0L) { "DeepFilterNet3 has already been released" }
        return DesktopDeepFilterBridge.nativeProcess(nativeHandle, input, output)
    }

    override fun setParameters(attenuationLimitDb: Float, postFilterBeta: Float) {
        check(nativeHandle != 0L) { "DeepFilterNet3 has already been released" }
        DesktopDeepFilterBridge.nativeSetParameters(
            nativeHandle,
            attenuationLimitDb,
            postFilterBeta,
        )
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        DesktopDeepFilterBridge.nativeDestroy(handle)
    }
}

internal object DesktopDeepFilterBridge {
    external fun nativeCreate(
        libraryPath: String,
        modelPath: String,
        attenuationLimitDb: Float,
    ): Long

    external fun nativeFrameSize(handle: Long): Int
    external fun nativeProcess(handle: Long, input: ShortArray, output: FloatArray): Float
    external fun nativeSetParameters(handle: Long, attenuationLimitDb: Float, postFilterBeta: Float)
    external fun nativeDestroy(handle: Long)
}
