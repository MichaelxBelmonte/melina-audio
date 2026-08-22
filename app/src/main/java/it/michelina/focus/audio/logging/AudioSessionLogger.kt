package it.michelina.focus.audio.logging

import android.content.Context
import it.michelina.focus.audio.AudioMetrics
import it.michelina.focus.audio.CaptureProfile
import it.michelina.focus.audio.ProcessorBackend
import it.michelina.focus.audio.ProcessorSettings
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.log10
import kotlin.math.pow

internal class AudioSessionLogger(
    context: Context,
    private val sampleRateHz: Int,
    private val backend: ProcessorBackend,
    private val captureProfile: CaptureProfile,
    private val inputRoute: String,
    private val outputRoute: String,
    private val onFinished: (LoggedSession) -> Unit,
) {
    private val startedAtEpochMs = System.currentTimeMillis()
    private val id = buildSessionId(startedAtEpochMs, backend)
    private val directory = SessionRepository(context).directoryFor(id).apply { mkdirs() }
    private val queue = LinkedBlockingQueue<LogItem>()
    private val audioBufferPool = ConcurrentLinkedQueue<AudioBuffers>()
    private val finishing = AtomicBoolean(false)
    private val worker = Thread(::writeLoop, "MichelinaLogWriter-$id").apply { start() }

    fun recordAudio(input: ShortArray, output: ShortArray) {
        if (finishing.get()) return
        val buffers = audioBufferPool.poll()
            ?.takeIf { it.input.size == input.size && it.output.size == output.size }
            ?: AudioBuffers(ShortArray(input.size), ShortArray(output.size))
        input.copyInto(buffers.input)
        output.copyInto(buffers.output)
        queue.offer(LogItem.Audio(buffers))
    }

    fun recordMetrics(metrics: AudioMetrics, settings: ProcessorSettings) {
        if (finishing.get()) return
        queue.offer(
            LogItem.Metric(
                elapsedMs = System.currentTimeMillis() - startedAtEpochMs,
                inputDbFs = metrics.inputDbFs,
                outputDbFs = metrics.outputDbFs,
                speechProbability = metrics.speechProbability,
                processingMs = metrics.averageProcessingMs,
                processingPeakMs = metrics.peakProcessingMs,
                denoiseDeltaDb = metrics.denoiseDeltaDb,
                signalChangedPercent = metrics.signalChangedPercent,
                presenceDeltaDb = metrics.presenceDeltaDb,
                quietBoostDb = metrics.effectiveQuietSpeechBoostDb,
                effectiveGainDb = metrics.effectiveGainDb,
                netDeltaDb = metrics.netOutputDeltaDb,
                gainSetDb = settings.gainDb,
                denoiseSet = settings.denoiseStrength,
                presenceSet = settings.clarity,
                quietSetDb = settings.quietSpeechBoostDb,
                mode = settings.mode.name,
                systemNs = metrics.systemNoiseSuppressorEnabled,
                systemAgc = metrics.systemAutomaticGainControlEnabled,
                underruns = metrics.underruns,
                processedFrames = metrics.processedFrames,
                denoiseMixPercent = metrics.denoiseMixPercent,
                presenceTargetDb = metrics.presenceTargetDb,
                quietRequestedDb = metrics.requestedQuietSpeechBoostDb,
                gainRequestedDb = metrics.requestedGainDb,
                algorithmLatencyMs = metrics.algorithmLatencyMs,
                systemNsAvailable = metrics.systemNoiseSuppressorAvailable,
                systemAgcAvailable = metrics.systemAutomaticGainControlAvailable,
                inputRoute = metrics.inputRoute,
                outputRoute = metrics.outputRoute,
                source = metrics.sourceDescription,
                transport = metrics.transport.name,
                vadRawProbability = metrics.vadRawProbability,
                vadSpeechDetected = metrics.vadSpeechDetected,
                vadProcessedWindows = metrics.vadProcessedWindows,
                vadInferenceMs = metrics.vadInferenceMs,
                vadModelName = metrics.vadModelName,
            ),
        )
    }

    fun recordSettings(settings: ProcessorSettings, initial: Boolean = false) {
        if (finishing.get()) return
        queue.offer(
            LogItem.Settings(
                elapsedMs = System.currentTimeMillis() - startedAtEpochMs,
                value = settingsKey(settings),
                initial = initial,
            ),
        )
    }

    fun recordOutcome(understood: Boolean) {
        if (!finishing.get()) queue.offer(LogItem.Outcome(understood))
    }

    fun finishAsync() {
        if (finishing.compareAndSet(false, true)) queue.offer(LogItem.Finish)
    }

    private fun writeLoop() {
        val inputWav = WavFile(File(directory, "input_raw.wav"), sampleRateHz)
        val outputWav = WavFile(File(directory, "output_processed.wav"), sampleRateHz)
        val metricsWriter = File(directory, SessionRepository.METRICS_FILE).bufferedWriter()
        val eventsWriter = File(directory, "events.csv").bufferedWriter()
        val statistics = Statistics()
        metricsWriter.write(METRIC_HEADER)
        metricsWriter.newLine()
        eventsWriter.write("elapsed_ms,event,value")
        eventsWriter.newLine()
        eventsWriter.write("0,start,${backend.name}")
        eventsWriter.newLine()

        try {
            while (true) {
                when (val item = queue.take()) {
                    is LogItem.Audio -> {
                        inputWav.write(item.buffers.input)
                        outputWav.write(item.buffers.output)
                        statistics.audioFrames++
                        audioBufferPool.offer(item.buffers)
                    }
                    is LogItem.Metric -> {
                        writeMetric(metricsWriter, item)
                        statistics.add(item)
                    }
                    is LogItem.Settings -> {
                        if (!item.initial) statistics.settingChanges++
                        eventsWriter.write(
                            "${item.elapsedMs},${if (item.initial) "settings_start" else "settings"},${item.value}",
                        )
                        eventsWriter.newLine()
                    }
                    is LogItem.Outcome -> {
                        if (item.understood) statistics.understood++ else statistics.missed++
                        val elapsed = System.currentTimeMillis() - startedAtEpochMs
                        eventsWriter.write("$elapsed,outcome,${if (item.understood) "understood" else "missed"}")
                        eventsWriter.newLine()
                    }
                    LogItem.Finish -> break
                }
            }
        } finally {
            val endedAt = System.currentTimeMillis()
            eventsWriter.write("${endedAt - startedAtEpochMs},stop,")
            eventsWriter.newLine()
            metricsWriter.close()
            eventsWriter.close()
            inputWav.close()
            outputWav.close()
            val session = statistics.toSession(
                id = id,
                startedAt = startedAtEpochMs,
                endedAt = endedAt,
                backend = backend,
                captureProfile = captureProfile,
                sampleRate = sampleRateHz,
                inputBytes = inputWav.dataBytes,
                outputBytes = outputWav.dataBytes,
            )
            writeSummary(session)
            onFinished(session)
        }
    }

    private fun writeMetric(writer: BufferedWriter, item: LogItem.Metric) {
        writer.write(
            listOf(
                item.elapsedMs,
                item.inputDbFs,
                item.outputDbFs,
                item.speechProbability,
                item.processingMs,
                item.processingPeakMs,
                item.denoiseDeltaDb,
                item.signalChangedPercent,
                item.presenceDeltaDb,
                item.quietBoostDb,
                item.effectiveGainDb,
                item.netDeltaDb,
                item.gainSetDb,
                item.denoiseSet,
                item.presenceSet,
                item.quietSetDb,
                item.mode,
                item.systemNs,
                item.systemAgc,
                item.underruns,
                item.processedFrames,
                item.denoiseMixPercent,
                item.presenceTargetDb,
                item.quietRequestedDb,
                item.gainRequestedDb,
                item.algorithmLatencyMs,
                item.systemNsAvailable,
                item.systemAgcAvailable,
                csvSafe(item.inputRoute),
                csvSafe(item.outputRoute),
                csvSafe(item.source),
                item.transport,
                item.vadRawProbability,
                item.vadSpeechDetected,
                item.vadProcessedWindows,
                item.vadInferenceMs,
                csvSafe(item.vadModelName),
            ).joinToString(","),
        )
        writer.newLine()
    }

    private fun writeSummary(session: LoggedSession) {
        val json = JSONObject().apply {
            put("id", session.id)
            put("startedAtEpochMs", session.startedAtEpochMs)
            put("endedAtEpochMs", session.endedAtEpochMs)
            put("durationMs", session.durationMs)
            put("backend", session.backend)
            put("captureProfile", session.captureProfile)
            put("sampleRateHz", session.sampleRateHz)
            put("inputRoute", inputRoute)
            put("outputRoute", outputRoute)
            put("audioFrames", session.audioFrames)
            put("inputAudioBytes", session.inputAudioBytes)
            put("outputAudioBytes", session.outputAudioBytes)
            put("metricPoints", session.metricPoints)
            put("settingChanges", session.settingChanges)
            put("understood", session.understood)
            put("missed", session.missed)
            put("averageInputDbFs", session.averageInputDbFs)
            put("averageOutputDbFs", session.averageOutputDbFs)
            put("averageSpeechProbability", session.averageSpeechProbability)
            put("averageProcessingMs", session.averageProcessingMs)
            put("peakProcessingMs", session.peakProcessingMs)
            put("averageDenoiseDeltaDb", session.averageDenoiseDeltaDb)
            put("averageNetDeltaDb", session.averageNetDeltaDb)
            put("vadModelName", session.vadModelName)
            put("averageVadInferenceMs", session.averageVadInferenceMs)
            put("vadProcessedWindows", session.vadProcessedWindows)
            put("speechDetectedPercent", session.speechDetectedPercent)
            put("startingUnderruns", session.startingUnderruns)
            put("endingUnderruns", session.endingUnderruns)
            put("underrunDelta", session.underrunDelta)
            put("audioRealtimePercent", session.audioRealtimePercent)
            put("dspUtilizationPercent", session.dspUtilizationPercent)
        }
        File(directory, SessionRepository.SUMMARY_FILE).writeText(json.toString(2))
    }

    private sealed interface LogItem {
        data class Audio(val buffers: AudioBuffers) : LogItem
        data class Outcome(val understood: Boolean) : LogItem
        data class Settings(
            val elapsedMs: Long,
            val value: String,
            val initial: Boolean,
        ) : LogItem
        data class Metric(
            val elapsedMs: Long,
            val inputDbFs: Float,
            val outputDbFs: Float,
            val speechProbability: Float,
            val processingMs: Float,
            val processingPeakMs: Float,
            val denoiseDeltaDb: Float,
            val signalChangedPercent: Float,
            val presenceDeltaDb: Float,
            val quietBoostDb: Float,
            val effectiveGainDb: Float,
            val netDeltaDb: Float,
            val gainSetDb: Float,
            val denoiseSet: Float,
            val presenceSet: Float,
            val quietSetDb: Float,
            val mode: String,
            val systemNs: Boolean,
            val systemAgc: Boolean,
            val underruns: Int,
            val processedFrames: Long,
            val denoiseMixPercent: Float,
            val presenceTargetDb: Float,
            val quietRequestedDb: Float,
            val gainRequestedDb: Float,
            val algorithmLatencyMs: Float,
            val systemNsAvailable: Boolean,
            val systemAgcAvailable: Boolean,
            val inputRoute: String,
            val outputRoute: String,
            val source: String,
            val transport: String,
            val vadRawProbability: Float,
            val vadSpeechDetected: Boolean,
            val vadProcessedWindows: Long,
            val vadInferenceMs: Float,
            val vadModelName: String,
        ) : LogItem
        data object Finish : LogItem
    }

    private data class AudioBuffers(
        val input: ShortArray,
        val output: ShortArray,
    )

    private class Statistics {
        var audioFrames = 0L
        var metricPoints = 0
        var settingChanges = 0
        var understood = 0
        var missed = 0
        private var sumInputPower = 0.0
        private var sumOutputPower = 0.0
        private var sumSpeech = 0.0
        private var sumProcessing = 0.0
        private var peakProcessing = 0f
        private var sumDenoise = 0.0
        private var sumVadInference = 0.0
        private var speechDetectedPoints = 0
        private var lastVadProcessedWindows = 0L
        private var vadModelName = "—"
        private var startingUnderruns: Int? = null
        private var endingUnderruns = 0

        fun add(metric: LogItem.Metric) {
            metricPoints++
            sumInputPower += dbToPower(metric.inputDbFs)
            sumOutputPower += dbToPower(metric.outputDbFs)
            sumSpeech += metric.speechProbability
            sumProcessing += metric.processingMs
            peakProcessing = max(peakProcessing, metric.processingPeakMs)
            sumDenoise += metric.denoiseDeltaDb
            sumVadInference += metric.vadInferenceMs
            if (metric.vadSpeechDetected) speechDetectedPoints++
            lastVadProcessedWindows = metric.vadProcessedWindows
            vadModelName = metric.vadModelName
            if (startingUnderruns == null) startingUnderruns = metric.underruns
            endingUnderruns = metric.underruns
        }

        fun toSession(
            id: String,
            startedAt: Long,
            endedAt: Long,
            backend: ProcessorBackend,
            captureProfile: CaptureProfile,
            sampleRate: Int,
            inputBytes: Long,
            outputBytes: Long,
        ): LoggedSession {
            val count = metricPoints.coerceAtLeast(1)
            val averageInputDb = powerToDb(sumInputPower / count)
            val averageOutputDb = powerToDb(sumOutputPower / count)
            val durationMs = endedAt - startedAt
            val audioDurationMs = inputBytes / Short.SIZE_BYTES.toDouble() / sampleRate * 1_000.0
            val frameSamples = if (audioFrames > 0) {
                inputBytes / Short.SIZE_BYTES.toDouble() / audioFrames
            } else {
                0.0
            }
            val frameDurationMs = frameSamples / sampleRate * 1_000.0
            val initialUnderruns = startingUnderruns ?: endingUnderruns
            return LoggedSession(
                id = id,
                startedAtEpochMs = startedAt,
                endedAtEpochMs = endedAt,
                durationMs = durationMs,
                backend = backend.name,
                captureProfile = captureProfile.name,
                sampleRateHz = sampleRate,
                audioFrames = audioFrames,
                inputAudioBytes = inputBytes,
                outputAudioBytes = outputBytes,
                metricPoints = metricPoints,
                settingChanges = settingChanges,
                understood = understood,
                missed = missed,
                averageInputDbFs = averageInputDb,
                averageOutputDbFs = averageOutputDb,
                averageSpeechProbability = (sumSpeech / count).toFloat(),
                averageProcessingMs = (sumProcessing / count).toFloat(),
                peakProcessingMs = peakProcessing,
                averageDenoiseDeltaDb = (sumDenoise / count).toFloat(),
                averageNetDeltaDb = averageOutputDb - averageInputDb,
                vadModelName = vadModelName,
                averageVadInferenceMs = (sumVadInference / count).toFloat(),
                vadProcessedWindows = lastVadProcessedWindows,
                speechDetectedPercent = speechDetectedPoints * 100f / count,
                startingUnderruns = initialUnderruns,
                endingUnderruns = endingUnderruns,
                underrunDelta = (endingUnderruns - initialUnderruns).coerceAtLeast(0),
                audioRealtimePercent = if (durationMs > 0) {
                    (audioDurationMs / durationMs * 100.0).toFloat()
                } else {
                    100f
                },
                dspUtilizationPercent = if (frameDurationMs > 0.0) {
                    (sumProcessing / count / frameDurationMs * 100.0).toFloat()
                } else {
                    0f
                },
            )
        }

        private fun dbToPower(db: Float): Double = 10.0.pow(db / 10.0)

        private fun powerToDb(power: Double): Float =
            (10.0 * log10(max(power, 1e-12))).toFloat().coerceAtLeast(-120f)
    }

    private class WavFile(file: File, private val sampleRate: Int) : AutoCloseable {
        private val output = RandomAccessFile(file, "rw")
        private var byteBuffer = ByteArray(0)
        var dataBytes: Long = 0
            private set

        init {
            output.setLength(0)
            writeHeader(0)
        }

        fun write(samples: ShortArray) {
            val byteCount = samples.size * 2
            if (byteBuffer.size < byteCount) byteBuffer = ByteArray(byteCount)
            for (index in samples.indices) {
                val value = samples[index].toInt()
                byteBuffer[index * 2] = (value and 0xff).toByte()
                byteBuffer[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
            }
            output.write(byteBuffer, 0, byteCount)
            dataBytes += byteCount
        }

        override fun close() {
            output.seek(0)
            writeHeader(dataBytes)
            output.close()
        }

        private fun writeHeader(dataSize: Long) {
            output.writeBytes("RIFF")
            writeIntLittleEndian((36L + dataSize).coerceAtMost(0xffff_ffffL))
            output.writeBytes("WAVEfmt ")
            writeIntLittleEndian(16)
            writeShortLittleEndian(1)
            writeShortLittleEndian(1)
            writeIntLittleEndian(sampleRate.toLong())
            writeIntLittleEndian(sampleRate * 2L)
            writeShortLittleEndian(2)
            writeShortLittleEndian(16)
            output.writeBytes("data")
            writeIntLittleEndian(dataSize.coerceAtMost(0xffff_ffffL))
        }

        private fun writeIntLittleEndian(value: Long) {
            repeat(4) { shift -> output.write(((value ushr (shift * 8)) and 0xff).toInt()) }
        }

        private fun writeShortLittleEndian(value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
        }
    }

    companion object {
        private const val METRIC_HEADER =
            "elapsed_ms,input_dbfs,output_dbfs,speech_probability,dsp_avg_ms,dsp_peak_ms," +
                "denoise_delta_db,signal_changed_percent,presence_delta_db,quiet_boost_db," +
                "effective_gain_db,net_delta_db,gain_set_db,denoise_set,presence_set," +
                "quiet_set_db,mode,system_ns,system_agc,underruns,processed_frames," +
                "denoise_mix_percent,presence_target_db,quiet_requested_db,gain_requested_db," +
                "algorithm_latency_ms,system_ns_available,system_agc_available,input_route," +
                "output_route,source,transport,vad_raw_probability,vad_speech_detected," +
                "vad_processed_windows,vad_inference_ms,vad_model"

        private fun settingsKey(settings: ProcessorSettings): String = listOf(
            settings.mode.name,
            "capture=${settings.captureProfile.name}",
            "vad=${settings.voiceDetectorBackend.name}",
            "fit=${settings.fittingProfile.name}",
            "gain=${settings.gainDb}",
            "denoise=${settings.denoiseStrength}",
            "presence=${settings.clarity}",
            "weak=${settings.quietSpeechBoostDb}",
            "ns=${settings.useSystemNoiseSuppressor}",
            "agc=${settings.useSystemAutomaticGainControl}",
        ).joinToString(";")

        private fun csvSafe(value: String): String = value
            .replace(',', ';')
            .replace('\n', ' ')

        private fun buildSessionId(epochMs: Long, backend: ProcessorBackend): String {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(epochMs))
            return "$timestamp-${backend.name.lowercase(Locale.US)}"
        }
    }
}
