package it.michelina.focus.audio

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class NeuralSpeechEnhancerInstrumentedTest {
    @Test
    fun bundledGtcrnInitializesAndStreamsOnArm64() {
        exerciseModel(ProcessorBackend.GTCRN_FAST, 16_000)
    }

    @Test
    fun bundledDpdfNet2InitializesAndStreamsOnArm64() {
        exerciseModel(ProcessorBackend.DPDFNET2_BALANCED, 16_000)
    }

    @Test
    fun bundledDpdfNet4InitializesAndStreamsOnArm64() {
        exerciseModel(ProcessorBackend.DPDFNET4_STRONG, 16_000)
    }

    @Test
    fun bundledDpdfNet48kInitializesAndStreamsOnArm64() {
        exerciseModel(ProcessorBackend.DPDFNET_HQ, 48_000)
    }

    @Test
    fun bundledDpdfNet8InitializesAndStreamsOnArm64() {
        exerciseModel(ProcessorBackend.DPDFNET8_SPEECH, 16_000)
    }

    @Test
    fun bundledRnnoiseNativeInitializesAndStreamsOnArm64() {
        exerciseRealtime48k(
            backend = ProcessorBackend.RNNOISE_NATIVE,
            enhancer = RnnoiseSpeechEnhancer(),
            expectedModelMarker = "RNNOISE 0.2",
        )
    }

    @Test
    fun bundledUlunasOnnxInitializesAndStreamsOnArm64() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        exerciseRealtime48k(
            backend = ProcessorBackend.ULUNAS_STREAM,
            enhancer = NativeRateNeuralSpeechEnhancer(
                context,
                ProcessorBackend.ULUNAS_STREAM,
                VoiceDetectorBackend.SILERO,
            ),
            expectedModelMarker = "UL-UNAS STREAM",
        )
    }

    @Test
    fun bundledDeepFilterNet3InitializesAndStreamsOnArm64() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        exerciseRealtime48k(
            backend = ProcessorBackend.DEEPFILTER3_HQ,
            enhancer = DeepFilterSpeechEnhancer(context, VoiceDetectorBackend.SILERO),
            expectedModelMarker = "DEEPFILTERNET3",
        )
    }

    @Test
    fun bundledTenVadRunsWithEnhancementOnArm64() {
        exerciseModel(
            backend = ProcessorBackend.GTCRN_FAST,
            sampleRate = 16_000,
            vadBackend = VoiceDetectorBackend.TEN_VAD,
        )
    }

    @Test
    fun gtcrnNative48kIoPathRunsWithAntiAliasedConversion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val wave = readPcm16Wave(testAssets.open("speech_with_noise_48k.wav").readBytes())
        val enhancer = NativeRateNeuralSpeechEnhancer(
            context,
            ProcessorBackend.GTCRN_FAST,
            VoiceDetectorBackend.SILERO,
        )
        val input = ShortArray(enhancer.frameSizeSamples)
        val output = ShortArray(input.size)
        val settings = ProcessorSettings(backend = ProcessorBackend.GTCRN_FAST)
        var peak = 0
        var windows = 0L

        try {
            val frameCount = (wave.samples.size + input.size - 1) / input.size
            repeat(frameCount) { frame ->
                input.fill(0)
                val sourceOffset = frame * input.size
                for (index in input.indices) {
                    if (sourceOffset + index < wave.samples.size) {
                        input[index] = wave.samples[sourceOffset + index]
                    }
                }
                val metrics = enhancer.process(input, output, settings)
                windows = metrics.vadProcessedWindows
                peak = maxOf(peak, output.maxOf { abs(it.toInt()) })
            }

            assertTrue("48 kHz wrapper produced silence", peak > 100)
            assertTrue("48 kHz wrapper VAD did not run", windows > 0)
            assertTrue("48 kHz wrapper did not report converter latency", enhancer.algorithmLatencySamples > input.size)
            assertTrue("48 kHz wrapper escaped limiter: $peak", peak <= (32_767 * 0.921f).toInt())
        } finally {
            enhancer.close()
        }
    }

    private fun exerciseModel(
        backend: ProcessorBackend,
        sampleRate: Int,
        vadBackend: VoiceDetectorBackend = VoiceDetectorBackend.SILERO,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val wave = readPcm16Wave(
            testAssets.open("speech_with_noise_${sampleRate / 1_000}k.wav").readBytes(),
        )
        assertEquals(sampleRate, wave.sampleRate)
        val enhancer = NeuralSpeechEnhancer(context, backend, vadBackend)
        val input = ShortArray(enhancer.frameSizeSamples)
        val output = ShortArray(input.size)
        val settings = ProcessorSettings(
            backend = backend,
            mode = ProcessingMode.VOICE_FOCUS,
            gainDb = 3f,
            denoiseStrength = 1f,
            clarity = 0.5f,
            useSystemNoiseSuppressor = false,
        )

        try {
            var outputPeak = 0
            var totalProcessNs = 0L
            var peakProcessNs = 0L
            var maximumSignalChange = 0f
            var maximumVadProbability = 0f
            var vadWindows = 0L
            val frameCount = (wave.samples.size + input.size - 1) / input.size
            repeat(frameCount) { frame ->
                input.fill(0)
                val sourceOffset = frame * input.size
                for (index in input.indices) {
                    if (sourceOffset + index < wave.samples.size) {
                        input[index] = wave.samples[sourceOffset + index]
                    }
                }
                val startedAt = System.nanoTime()
                val metrics = enhancer.process(input, output, settings)
                val processNs = System.nanoTime() - startedAt
                totalProcessNs += processNs
                peakProcessNs = maxOf(peakProcessNs, processNs)
                assertTrue(metrics.inputDbFs.isFinite())
                assertTrue(metrics.outputDbFs.isFinite())
                assertTrue(metrics.denoiseDeltaDb.isFinite())
                assertTrue(metrics.signalChangedPercent.isFinite())
                assertTrue(metrics.presenceDeltaDb.isFinite())
                assertTrue(metrics.effectiveGainDb.isFinite())
                assertTrue(metrics.vadInferenceMs.isFinite())
                assertEquals(
                    if (vadBackend == VoiceDetectorBackend.SILERO) {
                        NeuralVoiceDetector.SILERO_MODEL_NAME
                    } else {
                        NeuralVoiceDetector.TEN_MODEL_NAME
                    },
                    metrics.vadModelName,
                )
                assertEquals(frame + 1L, metrics.processedFrames)
                maximumSignalChange = maxOf(maximumSignalChange, metrics.signalChangedPercent)
                maximumVadProbability = maxOf(maximumVadProbability, metrics.speechProbability)
                vadWindows = metrics.vadProcessedWindows
                outputPeak = maxOf(outputPeak, output.maxOf { abs(it.toInt()) })
            }

            val averageMs = totalProcessNs / frameCount.toFloat() / 1_000_000f
            val peakMs = peakProcessNs / 1_000_000f
            var bypassTotalNs = 0L
            var bypassPeakNs = 0L
            val bypassSettings = settings.copy(mode = ProcessingMode.BYPASS)
            repeat(frameCount) { frame ->
                input.fill(0)
                val sourceOffset = frame * input.size
                for (index in input.indices) {
                    if (sourceOffset + index < wave.samples.size) {
                        input[index] = wave.samples[sourceOffset + index]
                    }
                }
                val startedAt = System.nanoTime()
                enhancer.process(input, output, bypassSettings)
                val processNs = System.nanoTime() - startedAt
                bypassTotalNs += processNs
                bypassPeakNs = maxOf(bypassPeakNs, processNs)
            }
            val bypassAverageMs = bypassTotalNs / frameCount.toFloat() / 1_000_000f
            val bypassPeakMs = bypassPeakNs / 1_000_000f
            Log.i(
                "MichelinaModelTest",
                "$backend focus=${averageMs}ms/$peakMs bypass=${bypassAverageMs}ms/$bypassPeakMs",
            )
            assertTrue("$backend should produce audible PCM", outputPeak > 100)
            assertTrue("$backend telemetry should observe a model change", maximumSignalChange > 0.1f)
            assertTrue("$backend $vadBackend did not run", vadWindows > 0)
            assertTrue(
                "$backend $vadBackend did not detect speech: $maximumVadProbability",
                maximumVadProbability > 0.5f,
            )
            assertTrue(
                "$backend limiter peak was $outputPeak",
                outputPeak <= (32_767 * 0.921f).toInt(),
            )
            assertEquals(input.size, enhancer.algorithmLatencySamples)
        } finally {
            enhancer.close()
        }
    }

    private fun exerciseRealtime48k(
        backend: ProcessorBackend,
        enhancer: RealtimeAudioProcessor,
        expectedModelMarker: String,
    ) {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val wave = readPcm16Wave(testAssets.open("speech_with_noise_48k.wav").readBytes())
        val input = ShortArray(enhancer.frameSizeSamples)
        val output = ShortArray(input.size)
        val settings = ProcessorSettings(
            backend = backend,
            gainDb = 0f,
            denoiseStrength = 1f,
            clarity = 0f,
            quietSpeechBoostDb = 0f,
        )
        var outputPeak = 0
        var maximumSignalChange = 0f
        var processedFrames = 0L
        var modelName = ""
        var totalNs = 0L

        try {
            val frameCount = (wave.samples.size + input.size - 1) / input.size
            repeat(frameCount) { frame ->
                input.fill(0)
                val sourceOffset = frame * input.size
                val count = minOf(input.size, wave.samples.size - sourceOffset)
                wave.samples.copyInto(
                    input,
                    destinationOffset = 0,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + count,
                )
                val started = System.nanoTime()
                val metrics = enhancer.process(input, output, settings)
                totalNs += System.nanoTime() - started
                assertTrue(metrics.inputDbFs.isFinite())
                assertTrue(metrics.outputDbFs.isFinite())
                assertTrue(metrics.vadRawProbability.isFinite())
                assertTrue(metrics.signalChangedPercent.isFinite())
                maximumSignalChange = maxOf(maximumSignalChange, metrics.signalChangedPercent)
                outputPeak = maxOf(outputPeak, output.maxOf { abs(it.toInt()) })
                processedFrames = metrics.processedFrames
                modelName = metrics.vadModelName
            }
            val averageMs = totalNs / frameCount.toFloat() / 1_000_000f
            val frameMs = input.size * 1_000f / 48_000f
            Log.i(
                "MichelinaModelTest",
                "$backend average=${averageMs}ms frame=${frameMs}ms change=$maximumSignalChange%",
            )
            assertEquals(frameCount.toLong(), processedFrames)
            assertTrue("$backend did not run the model: $modelName", modelName.contains(expectedModelMarker))
            assertTrue("$backend ha prodotto silenzio", outputPeak > 100)
            assertTrue("$backend did not modify the signal", maximumSignalChange > 0.1f)
            assertTrue("$backend troppo lento: $averageMs ms / $frameMs ms", averageMs < frameMs * 2f)
            assertTrue("$backend limiter peak $outputPeak", outputPeak <= (32_767 * 0.921f).toInt())
        } finally {
            enhancer.close()
        }
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
            if (chunkName == "fmt " && chunkSize >= 16) {
                require(buffer.getShort(payloadOffset).toInt() == 1) { "WAV is not PCM" }
                channels = buffer.getShort(payloadOffset + 2).toInt()
                sampleRate = buffer.getInt(payloadOffset + 4)
                bitsPerSample = buffer.getShort(payloadOffset + 14).toInt()
            } else if (chunkName == "data") {
                dataOffset = payloadOffset
                dataSize = minOf(chunkSize, bytes.size - payloadOffset)
                break
            }
            offset = payloadOffset + chunkSize + (chunkSize and 1)
        }
        require(sampleRate > 0 && channels == 1 && bitsPerSample == 16)
        require(dataOffset >= 0 && dataSize >= 2)
        val samples = ShortArray(dataSize / 2) { index ->
            buffer.getShort(dataOffset + index * 2)
        }
        return WaveData(sampleRate, samples)
    }

    private data class WaveData(val sampleRate: Int, val samples: ShortArray)
}
