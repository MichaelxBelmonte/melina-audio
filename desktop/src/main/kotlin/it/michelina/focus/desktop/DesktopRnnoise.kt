package it.michelina.focus.desktop

import it.michelina.focus.audio.RnnoiseAudioProcessor
import it.michelina.focus.audio.RnnoiseInference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class DesktopRnnoiseInference : RnnoiseInference {
    private var nativeHandle: Long
    override val frameSizeSamples: Int

    init {
        DesktopNativeLibrary.load()
        nativeHandle = DesktopRnnoiseBridge.nativeCreate()
        frameSizeSamples = DesktopRnnoiseBridge.nativeFrameSize()
        check(nativeHandle != 0L) { "RNNoise: native desktop initialization failed" }
        check(frameSizeSamples == RnnoiseAudioProcessor.FRAME_SIZE) {
            "RNNoise: frame $frameSizeSamples instead of ${RnnoiseAudioProcessor.FRAME_SIZE}"
        }
    }

    override fun process(input: ShortArray, output: FloatArray): Float {
        check(nativeHandle != 0L) { "RNNoise has already been released" }
        return DesktopRnnoiseBridge.nativeProcess(nativeHandle, input, output)
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        DesktopRnnoiseBridge.nativeDestroy(handle)
    }
}

internal object DesktopRnnoiseBridge {
    external fun nativeCreate(): Long
    external fun nativeFrameSize(): Int
    external fun nativeProcess(handle: Long, input: ShortArray, output: FloatArray): Float
    external fun nativeDestroy(handle: Long)
}

internal object DesktopNativeLibrary {
    @Volatile
    private var loaded = false
    private val temporaryDirectory: Path by lazy {
        Files.createTempDirectory("michelina-native-").also { it.toFile().deleteOnExit() }
    }

    @Synchronized
    fun load() {
        if (loaded) return
        val libraryName = System.mapLibraryName("michelina_desktop_audio")
        val resourcePath = "native/${platformId()}/$libraryName"
        val target = materialize(resourcePath, libraryName)
        System.load(target.toAbsolutePath().toString())
        loaded = true
    }

    @Synchronized
    fun onnxRuntimePath(): Path {
        val platform = platformId()
        val names = when {
            platform.startsWith("osx-") -> listOf(
                "libonnxruntime.1.27.0.dylib",
                "libonnxruntime.dylib",
            )
            platform.startsWith("win-") -> listOf("onnxruntime.dll")
            else -> listOf("libonnxruntime.so", "libonnxruntime.so.1.27.0")
        }
        for (name in names) {
            val resourcePath = "sherpa-onnx/native/$platform/$name"
            if (javaClass.classLoader.getResource(resourcePath) != null) {
                return materialize(resourcePath, name)
            }
        }
        error("ONNX Runtime desktop library not found for $platform")
    }

    fun hasDeepFilter(): Boolean {
        val resourcePath = "native/${platformId()}/${deepFilterLibraryName()}"
        return javaClass.classLoader.getResource(resourcePath) != null
    }

    @Synchronized
    fun deepFilterPath(): Path {
        val name = deepFilterLibraryName()
        return materialize("native/${platformId()}/$name", name)
    }

    private fun deepFilterLibraryName(): String {
        val platform = platformId()
        return when {
            platform.startsWith("osx-") -> "libdf.dylib"
            platform.startsWith("win-") -> "df.dll"
            else -> "libdf.so"
        }
    }

    private fun materialize(resourcePath: String, fileName: String): Path {
        val target = temporaryDirectory.resolve(fileName)
        if (Files.isRegularFile(target)) return target
        val input = requireNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) {
            "Native desktop library not found: $resourcePath"
        }
        input.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        target.toFile().deleteOnExit()
        return target
    }

    internal fun platformId(): String {
        val os = System.getProperty("os.name").lowercase()
        val architecture = System.getProperty("os.arch").lowercase()
        val arm64 = architecture == "aarch64" || architecture == "arm64"
        return when {
            os.contains("mac") -> if (arm64) "osx-aarch64" else "osx-x64"
            os.contains("win") -> if (arm64) "win-arm64" else "win-x64"
            os.contains("linux") -> if (arm64) "linux-aarch64" else "linux-x64"
            else -> error("Desktop platform not supported: $os $architecture")
        }
    }
}
