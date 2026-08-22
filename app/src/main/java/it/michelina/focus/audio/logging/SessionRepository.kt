package it.michelina.focus.audio.logging

import android.content.Context
import org.json.JSONObject
import java.io.File

data class LoggedSession(
    val id: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationMs: Long,
    val backend: String,
    val captureProfile: String,
    val sampleRateHz: Int,
    val audioFrames: Long,
    val inputAudioBytes: Long,
    val outputAudioBytes: Long,
    val metricPoints: Int,
    val settingChanges: Int,
    val understood: Int,
    val missed: Int,
    val averageInputDbFs: Float,
    val averageOutputDbFs: Float,
    val averageSpeechProbability: Float,
    val averageProcessingMs: Float,
    val peakProcessingMs: Float,
    val averageDenoiseDeltaDb: Float,
    val averageNetDeltaDb: Float,
    val vadModelName: String = "—",
    val averageVadInferenceMs: Float = 0f,
    val vadProcessedWindows: Long = 0,
    val speechDetectedPercent: Float = 0f,
    val startingUnderruns: Int = 0,
    val endingUnderruns: Int = 0,
    val underrunDelta: Int = 0,
    val audioRealtimePercent: Float = 100f,
    val dspUtilizationPercent: Float = 0f,
)

data class SessionMetricPoint(
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
)

class SessionRepository(context: Context) {
    val rootDirectory: File = File(
        context.getExternalFilesDir("sessions") ?: context.filesDir,
        if (context.getExternalFilesDir("sessions") == null) "sessions" else "",
    ).apply { mkdirs() }

    fun directoryFor(id: String): File = File(rootDirectory, id)

    fun listSessions(): List<LoggedSession> = rootDirectory.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isDirectory }
        .mapNotNull { directory ->
            runCatching { parseSummary(File(directory, SUMMARY_FILE).readText()) }.getOrNull()
        }
        .sortedByDescending { it.startedAtEpochMs }
        .toList()

    fun loadMetrics(id: String): List<SessionMetricPoint> {
        val file = File(directoryFor(id), METRICS_FILE)
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.drop(1).mapNotNull(::parseMetricLine).toList()
        }
    }

    private fun parseMetricLine(line: String): SessionMetricPoint? {
        val values = line.split(',')
        if (values.size < 12) return null
        return runCatching {
            SessionMetricPoint(
                elapsedMs = values[0].toLong(),
                inputDbFs = values[1].toFloat(),
                outputDbFs = values[2].toFloat(),
                speechProbability = values[3].toFloat(),
                processingMs = values[4].toFloat(),
                processingPeakMs = values[5].toFloat(),
                denoiseDeltaDb = values[6].toFloat(),
                signalChangedPercent = values[7].toFloat(),
                presenceDeltaDb = values[8].toFloat(),
                quietBoostDb = values[9].toFloat(),
                effectiveGainDb = values[10].toFloat(),
                netDeltaDb = values[11].toFloat(),
            )
        }.getOrNull()
    }

    companion object {
        const val SUMMARY_FILE = "summary.json"
        const val METRICS_FILE = "metrics.csv"

        internal fun parseSummary(text: String): LoggedSession {
            val json = JSONObject(text)
            return LoggedSession(
                id = json.getString("id"),
                startedAtEpochMs = json.getLong("startedAtEpochMs"),
                endedAtEpochMs = json.getLong("endedAtEpochMs"),
                durationMs = json.getLong("durationMs"),
                backend = json.getString("backend"),
                captureProfile = json.getString("captureProfile"),
                sampleRateHz = json.getInt("sampleRateHz"),
                audioFrames = json.getLong("audioFrames"),
                inputAudioBytes = json.getLong("inputAudioBytes"),
                outputAudioBytes = json.getLong("outputAudioBytes"),
                metricPoints = json.getInt("metricPoints"),
                settingChanges = json.getInt("settingChanges"),
                understood = json.getInt("understood"),
                missed = json.getInt("missed"),
                averageInputDbFs = json.getDouble("averageInputDbFs").toFloat(),
                averageOutputDbFs = json.getDouble("averageOutputDbFs").toFloat(),
                averageSpeechProbability = json.getDouble("averageSpeechProbability").toFloat(),
                averageProcessingMs = json.getDouble("averageProcessingMs").toFloat(),
                peakProcessingMs = json.getDouble("peakProcessingMs").toFloat(),
                averageDenoiseDeltaDb = json.getDouble("averageDenoiseDeltaDb").toFloat(),
                averageNetDeltaDb = json.getDouble("averageNetDeltaDb").toFloat(),
                vadModelName = json.optString("vadModelName", "—"),
                averageVadInferenceMs = json.optDouble("averageVadInferenceMs", 0.0).toFloat(),
                vadProcessedWindows = json.optLong("vadProcessedWindows", 0),
                speechDetectedPercent = json.optDouble("speechDetectedPercent", 0.0).toFloat(),
                startingUnderruns = json.optInt("startingUnderruns", 0),
                endingUnderruns = json.optInt("endingUnderruns", 0),
                underrunDelta = json.optInt("underrunDelta", 0),
                audioRealtimePercent = json.optDouble("audioRealtimePercent", 100.0).toFloat(),
                dspUtilizationPercent = json.optDouble("dspUtilizationPercent", 0.0).toFloat(),
            )
        }
    }
}
