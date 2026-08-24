package it.michelina.focus.desktop

import it.michelina.focus.audio.UlunasAudioProcessor
import it.michelina.focus.audio.UlunasInference

/** ONNX Runtime C-API adapter using the runtime already distributed with sherpa-onnx. */
internal class DesktopUlunasInference : UlunasInference {
    private var nativeHandle: Long

    init {
        DesktopNativeLibrary.load()
        val runtimePath = DesktopNativeLibrary.onnxRuntimePath()
        val modelPath = DesktopModelRepository.materialize(UlunasAudioProcessor.MODEL_ASSET)
        nativeHandle = DesktopUlunasBridge.nativeCreate(
            runtimePath.toAbsolutePath().toString(),
            modelPath.toAbsolutePath().toString(),
        )
        check(nativeHandle != 0L) { "UL-UNAS: native desktop initialization failed" }
    }

    override fun process(
        inputSpectrum: FloatArray,
        convCache: FloatArray,
        tfaCache: FloatArray,
        interCache: FloatArray,
        enhancedSpectrum: FloatArray,
    ) {
        check(nativeHandle != 0L) { "UL-UNAS has already been released" }
        DesktopUlunasBridge.nativeProcess(
            nativeHandle,
            inputSpectrum,
            convCache,
            tfaCache,
            interCache,
            enhancedSpectrum,
        )
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        DesktopUlunasBridge.nativeDestroy(handle)
    }
}

internal object DesktopUlunasBridge {
    external fun nativeCreate(runtimePath: String, modelPath: String): Long

    external fun nativeProcess(
        handle: Long,
        inputSpectrum: FloatArray,
        convCache: FloatArray,
        tfaCache: FloatArray,
        interCache: FloatArray,
        enhancedSpectrum: FloatArray,
    )

    external fun nativeDestroy(handle: Long)
}
