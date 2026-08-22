package it.michelina.focus.audio.logging

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.michelina.focus.audio.AudioMetrics
import it.michelina.focus.audio.CaptureProfile
import it.michelina.focus.audio.ProcessorBackend
import it.michelina.focus.audio.ProcessorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioSessionLoggerInstrumentedTest {
    @Test
    fun storesAudioTelemetrySettingsAndOutcomes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val completed = CountDownLatch(1)
        var saved: LoggedSession? = null
        val logger = AudioSessionLogger(
            context = context,
            sampleRateHz = 16_000,
            backend = ProcessorBackend.GTCRN_FAST,
            captureProfile = CaptureProfile.RAW,
            inputRoute = "test input",
            outputRoute = "test output",
            onFinished = {
                saved = it
                completed.countDown()
            },
        )
        val input = ShortArray(160) { it.toShort() }
        val output = ShortArray(160) { (it * 2).toShort() }
        val firstSettings = ProcessorSettings(gainDb = 3f)
        val secondSettings = firstSettings.copy(gainDb = 6f, denoiseStrength = 1f)

        logger.recordSettings(firstSettings, initial = true)
        logger.recordAudio(input, output)
        logger.recordMetrics(testMetrics(frame = 1), firstSettings)
        logger.recordSettings(secondSettings)
        logger.recordAudio(input, output)
        logger.recordMetrics(testMetrics(frame = 2), secondSettings)
        logger.recordOutcome(understood = true)
        logger.recordOutcome(understood = false)
        logger.finishAsync()

        assertTrue("Logger did not finish", completed.await(5, TimeUnit.SECONDS))
        val session = requireNotNull(saved)
        val directory = SessionRepository(context).directoryFor(session.id)
        try {
            assertEquals(2, session.metricPoints)
            assertEquals(2L, session.audioFrames)
            assertEquals(1, session.settingChanges)
            assertEquals(1, session.understood)
            assertEquals(1, session.missed)
            assertEquals(640L, session.inputAudioBytes)
            assertEquals(640L, session.outputAudioBytes)
            assertEquals("SILERO VAD", session.vadModelName)
            assertEquals(0.2f, session.averageVadInferenceMs, 0.001f)
            assertEquals(4L, session.vadProcessedWindows)
            assertEquals(100f, session.speechDetectedPercent, 0.001f)
            assertEquals(5, session.startingUnderruns)
            assertEquals(6, session.endingUnderruns)
            assertEquals(1, session.underrunDelta)
            assertEquals(12f, session.dspUtilizationPercent, 0.001f)
            assertEquals(-23.0103f, session.averageInputDbFs, 0.001f)
            assertEquals(-13.0103f, session.averageOutputDbFs, 0.001f)
            assertEquals(10f, session.averageNetDeltaDb, 0.001f)
            assertEquals(684L, File(directory, "input_raw.wav").length())
            assertEquals(684L, File(directory, "output_processed.wav").length())
            val summary = File(directory, "summary.json").readText()
            assertTrue(summary.contains("GTCRN_FAST"))
            assertTrue(summary.contains("SILERO VAD"))
            val metrics = File(directory, "metrics.csv").readLines()
            assertEquals(3, metrics.size)
            assertTrue(metrics.first().contains("vad_model"))
            assertTrue(metrics.last().contains("SILERO VAD"))
            assertTrue(File(directory, "events.csv").readText().contains("outcome,understood"))
        } finally {
            val keepSession = InstrumentationRegistry.getArguments()
                .getString("keepSession")
                .toBoolean()
            if (!keepSession) directory.deleteRecursively()
        }
    }

    private fun testMetrics(frame: Long) = AudioMetrics(
        running = true,
        backend = ProcessorBackend.GTCRN_FAST,
        captureProfile = CaptureProfile.RAW,
        inputDbFs = if (frame == 1L) -120f else -20f,
        outputDbFs = if (frame == 1L) -120f else -10f,
        speechProbability = 0.8f,
        processedFrames = frame,
        denoiseDeltaDb = -3f,
        signalChangedPercent = 45f,
        presenceDeltaDb = 1.2f,
        effectiveQuietSpeechBoostDb = 2f,
        effectiveGainDb = 2.8f,
        netOutputDeltaDb = 7f,
        averageProcessingMs = 1.2f,
        peakProcessingMs = 2.4f,
        vadRawProbability = 0.72f,
        vadSpeechDetected = true,
        vadProcessedWindows = frame * 2,
        vadInferenceMs = 0.2f,
        vadModelName = "SILERO VAD",
        underruns = frame.toInt() + 4,
    )
}
