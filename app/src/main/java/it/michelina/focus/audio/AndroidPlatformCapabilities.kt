package it.michelina.focus.audio

import android.os.Build

/** Backend availability for the native libraries packaged in each Android ABI. */
object AndroidPlatformCapabilities {
    private val primaryAbi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    fun supports(backend: ProcessorBackend): Boolean = when (backend) {
        // libDF and the custom ONNX Runtime Java bridge are currently packaged for ARM64 only.
        ProcessorBackend.DEEPFILTER3_HQ,
        ProcessorBackend.ULUNAS_STREAM -> primaryAbi == "arm64-v8a"
        else -> true
    }
}
