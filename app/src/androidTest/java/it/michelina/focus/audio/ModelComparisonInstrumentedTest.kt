package it.michelina.focus.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

/**
 * Lab-only equal-input comparison. Push a 48 kHz mono PCM16 WAV to the app's external
 * `files/benchmark/input.wav`, run this test, then pull the output directory.
 */
@RunWith(AndroidJUnit4::class)
class ModelComparisonInstrumentedTest {
    @Test
    fun renderSameRawCaptureThroughEveryBackend() {
        assumeTrue(
            "Equal-input benchmark is opt-in",
            InstrumentationRegistry.getArguments().getString("modelComparison") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = checkNotNull(context.getExternalFilesDir("benchmark"))
        val inputFile = File(directory, INPUT_FILE)
        assumeTrue(
            "Missing $inputFile. Use scripts/benchmark_models_on_pixel.sh <48k-mono-pcm16.wav>",
            inputFile.isFile,
        )
        val wave = readPcm16Wave(inputFile.readBytes())
        assertEquals(48_000, wave.sampleRate)
        val resultRows = mutableListOf(
            "backend,frames,frame_ms,average_dsp_ms,peak_dsp_ms,budget_percent,output_peak",
        )

        for (backend in ProcessorBackend.entries) {
            val processor = createProcessor(context, backend)
            try {
                val input = ShortArray(processor.frameSizeSamples)
                val output = ShortArray(processor.frameSizeSamples)
                val rendered = ShortArray(wave.samples.size)
                val settings = ProcessorSettings(
                    backend = backend,
                    mode = ProcessingMode.VOICE_FOCUS,
                    gainDb = 0f,
                    denoiseStrength = 1f,
                    clarity = 0f,
                    quietSpeechBoostDb = 0f,
                    useSystemNoiseSuppressor = false,
                    useSystemAutomaticGainControl = false,
                )
                repeat(4) { processor.process(input, output, settings) }

                val frameCount = (wave.samples.size + input.size - 1) / input.size
                var totalNs = 0L
                var peakNs = 0L
                repeat(frameCount) { frame ->
                    input.fill(0)
                    val sourceOffset = frame * input.size
                    val count = minOf(input.size, wave.samples.size - sourceOffset)
                    wave.samples.copyInto(input, endIndex = sourceOffset + count, startIndex = sourceOffset)
                    val started = System.nanoTime()
                    processor.process(input, output, settings)
                    val elapsed = System.nanoTime() - started
                    totalNs += elapsed
                    peakNs = maxOf(peakNs, elapsed)
                    output.copyInto(
                        rendered,
                        destinationOffset = sourceOffset,
                        endIndex = count,
                    )
                }

                val frameMs = input.size * 1_000f / wave.sampleRate
                val averageMs = totalNs / frameCount.toFloat() / 1_000_000f
                val peakMs = peakNs / 1_000_000f
                val outputPeak = rendered.maxOf { abs(it.toInt()) }
                writePcm16Wave(
                    File(directory, "output_${backend.name.lowercase(Locale.US)}.wav"),
                    wave.sampleRate,
                    rendered,
                )
                resultRows += String.format(
                    Locale.US,
                    "%s,%d,%.3f,%.4f,%.4f,%.1f,%d",
                    backend.name,
                    frameCount,
                    frameMs,
                    averageMs,
                    peakMs,
                    averageMs / frameMs * 100f,
                    outputPeak,
                )
            } finally {
                processor.close()
            }
        }
        File(directory, "benchmark.csv").writeText(resultRows.joinToString("\n", postfix = "\n"))
    }

    private fun createProcessor(
        context: android.content.Context,
        backend: ProcessorBackend,
    ): RealtimeAudioProcessor = when (backend) {
        ProcessorBackend.CLASSIC_DSP -> SpectralSpeechEnhancer(
            sampleRate = 48_000,
            context = context,
            voiceDetectorBackend = VoiceDetectorBackend.SILERO,
        )
        ProcessorBackend.DPDFNET_HQ -> NeuralSpeechEnhancer(
            context,
            backend,
            VoiceDetectorBackend.SILERO,
        )
        ProcessorBackend.RNNOISE_NATIVE -> RnnoiseSpeechEnhancer()
        ProcessorBackend.DEEPFILTER3_HQ -> DeepFilterSpeechEnhancer(
            context,
            VoiceDetectorBackend.SILERO,
        )
        ProcessorBackend.ULUNAS_STREAM -> NativeRateNeuralSpeechEnhancer(
            context,
            backend,
            VoiceDetectorBackend.SILERO,
        )
        ProcessorBackend.GTCRN_FAST,
        ProcessorBackend.DPDFNET2_BALANCED,
        ProcessorBackend.DPDFNET4_STRONG,
        ProcessorBackend.DPDFNET8_SPEECH -> NativeRateNeuralSpeechEnhancer(
            context,
            backend,
            VoiceDetectorBackend.SILERO,
        )
    }

    private fun readPcm16Wave(bytes: ByteArray): WaveData {
        require(bytes.size >= 44 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF")
        require(bytes.copyOfRange(8, 12).decodeToString() == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val chunkName = bytes.copyOfRange(offset, offset + 4).decodeToString()
            val chunkSize = buffer.getInt(offset + 4)
            val payloadOffset = offset + 8
            when (chunkName) {
                "fmt " -> if (chunkSize >= 16) {
                    require(buffer.getShort(payloadOffset).toInt() == 1) { "WAV must be PCM" }
                    channels = buffer.getShort(payloadOffset + 2).toInt()
                    sampleRate = buffer.getInt(payloadOffset + 4)
                    bitsPerSample = buffer.getShort(payloadOffset + 14).toInt()
                }
                "data" -> {
                    dataOffset = payloadOffset
                    dataSize = minOf(chunkSize, bytes.size - payloadOffset)
                    break
                }
            }
            offset = payloadOffset + chunkSize + (chunkSize and 1)
        }
        require(sampleRate > 0 && channels == 1 && bitsPerSample == 16) {
            "Expected mono PCM16 WAV"
        }
        require(dataOffset >= 0 && dataSize >= 2)
        return WaveData(
            sampleRate,
            ShortArray(dataSize / 2) { buffer.getShort(dataOffset + it * 2) },
        )
    }

    private fun writePcm16Wave(file: File, sampleRate: Int, samples: ShortArray) {
        val dataBytes = samples.size * Short.SIZE_BYTES
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(1.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2.toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        for (sample in samples) buffer.putShort(sample)
        file.writeBytes(buffer.array())
    }

    private data class WaveData(val sampleRate: Int, val samples: ShortArray)

    companion object {
        private const val INPUT_FILE = "input.wav"
    }
}
