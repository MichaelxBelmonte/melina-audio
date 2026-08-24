package it.michelina.focus.desktop

import it.michelina.focus.audio.ProcessorBackend
import it.michelina.focus.audio.ProcessorSettings
import it.michelina.focus.audio.ProcessingMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.sin

class DesktopNeuralProcessorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun everyAdvertisedNonClassicBackendRunsThroughItsDesktopNativeRuntime() {
        val previousModelDirectory = System.getProperty("melina.modelDir")
        System.setProperty("melina.modelDir", temporaryFolder.newFolder("models").absolutePath)
        try {
            val nativeBackends = DesktopProcessorFactory.supportedBackends
                .filterNot { it == ProcessorBackend.CLASSIC_DSP }
            for (backend in nativeBackends) {
                val processor = DesktopProcessorFactory.create(backend)
                processor.use {
                    assertTrue(
                        "$backend unexpected native-rate frame ${processor.frameSizeSamples}",
                        processor.frameSizeSamples == 480 || processor.frameSizeSamples == 768,
                    )
                    val input = ShortArray(processor.frameSizeSamples)
                    val output = ShortArray(processor.frameSizeSamples)
                    val settings = ProcessorSettings(
                        backend = backend,
                        mode = ProcessingMode.BYPASS,
                    )
                    var metrics = processor.process(input, output, settings)
                    var emittedAudio = false
                    repeat(30) { frame ->
                        for (index in input.indices) {
                            val sample = frame * input.size + index
                            input[index] = (
                                5_000.0 * sin(2.0 * PI * 900.0 * sample / 48_000.0)
                                ).toInt().toShort()
                        }
                        metrics = processor.process(input, output, settings)
                        emittedAudio = emittedAudio || output.any { it != 0.toShort() }
                    }
                    assertTrue("$backend input metrics", metrics.inputDbFs.isFinite())
                    assertTrue("$backend output metrics", metrics.outputDbFs.isFinite())
                    assertTrue("$backend frame counter", metrics.processedFrames >= 31)
                    assertTrue("$backend emitted only silence", emittedAudio)
                }
            }
        } finally {
            if (previousModelDirectory == null) {
                System.clearProperty("melina.modelDir")
            } else {
                System.setProperty("melina.modelDir", previousModelDirectory)
            }
        }
    }
}
