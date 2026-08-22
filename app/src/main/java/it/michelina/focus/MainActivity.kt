package it.michelina.focus

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import it.michelina.focus.audio.AudioEngine
import it.michelina.focus.audio.AudioInputOption
import it.michelina.focus.audio.AudioMetrics
import it.michelina.focus.audio.AudioTransport
import it.michelina.focus.audio.CaptureProfile
import it.michelina.focus.audio.FittingProfile
import it.michelina.focus.audio.MAX_PRESENCE_BOOST_DB
import it.michelina.focus.audio.ProcessingMode
import it.michelina.focus.audio.ProcessorBackend
import it.michelina.focus.audio.ProcessorSettings
import it.michelina.focus.audio.VoiceDetectorBackend
import it.michelina.focus.audio.logging.LoggedSession
import it.michelina.focus.audio.logging.SessionRepository
import it.michelina.focus.ui.MonitorSeries
import it.michelina.focus.ui.RealtimeWaveformView
import it.michelina.focus.ui.SessionPlotMode
import it.michelina.focus.ui.SessionPlotView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ListeningPreset(
    val label: String,
    val description: String,
    val backend: ProcessorBackend,
    val capture: CaptureProfile,
    val vad: VoiceDetectorBackend,
    val fitting: FittingProfile,
    val gainDb: Float,
    val denoise: Float,
    val clarity: Float,
    val weakVoiceDb: Float,
    val systemNs: Boolean,
    val systemAgc: Boolean,
) {
    HOME(
        "QUIET HOME\nGTCRN",
        "Daily use · fast, stable, and with good battery headroom.",
        ProcessorBackend.GTCRN_FAST,
        CaptureProfile.RAW,
        VoiceDetectorBackend.SILERO,
        FittingProfile.SPEECH,
        3f, 1f, 0.55f, 4f, false, false,
    ),
    STEADY_NOISE(
        "STEADY NOISE\nRNNOISE NATIVE",
        "Fans, traffic, or appliances · lightweight full-band Xiph RNN.",
        ProcessorBackend.RNNOISE_NATIVE,
        CaptureProfile.RAW,
        VoiceDetectorBackend.SILERO,
        FittingProfile.SPEECH,
        3f, 1f, 0.55f, 5f, false, false,
    ),
    CHALLENGER(
        "AI CHALLENGER\nUL-UNAS STREAM",
        "Ultra-light 2026 model · run an A/B test against GTCRN at equal volume.",
        ProcessorBackend.ULUNAS_STREAM,
        CaptureProfile.RAW,
        VoiceDetectorBackend.SILERO,
        FittingProfile.SPEECH,
        3f, 1f, 0.55f, 4f, false, false,
    ),
    DEEP_HQ(
        "LAB QUALITY\nDEEPFILTER3",
        "Full-band deep filtering · experimental maximum quality; monitor LOAD and temperature.",
        ProcessorBackend.DEEPFILTER3_HQ,
        CaptureProfile.RAW,
        VoiceDetectorBackend.SILERO,
        FittingProfile.NATURAL,
        1f, 1f, 0.35f, 3f, false, false,
    ),
    DISTANT(
        "DISTANT VOICES\nDPDF2",
        "Distant speaker · raises quiet voices; AGC may also raise noise.",
        ProcessorBackend.DPDFNET2_BALANCED,
        CaptureProfile.VOICE_RECOGNITION,
        VoiceDetectorBackend.SILERO,
        FittingProfile.SPEECH,
        2f, 0.92f, 0.62f, 8f, false, true,
    ),
    NOISY(
        "NOISY PLACE\nDPDF4",
        "Bar, kitchen, or street · strong reduction; check LOAD after a few minutes.",
        ProcessorBackend.DPDFNET4_STRONG,
        CaptureProfile.VOICE_RECOGNITION,
        VoiceDetectorBackend.SILERO,
        FittingProfile.CONSONANTS,
        1f, 1f, 0.68f, 5f, false, false,
    ),
    MAXIMUM(
        "MAX CLEANUP\nDPDF8",
        "Heaviest 16 kHz model · quality test; easy to OVERLOAD when the phone is hot.",
        ProcessorBackend.DPDFNET8_SPEECH,
        CaptureProfile.RAW,
        VoiceDetectorBackend.SILERO,
        FittingProfile.SPEECH,
        1f, 1f, 0.55f, 4f, false, false,
    ),
    NATURAL(
        "NATURAL SOUND\nFULL BAND 48K",
        "Preserves more high frequencies · natural, but very demanding on the Pixel.",
        ProcessorBackend.DPDFNET_HQ,
        CaptureProfile.RAW,
        VoiceDetectorBackend.SILERO,
        FittingProfile.NATURAL,
        1f, 0.78f, 0.35f, 3f, false, false,
    ),
    COMPATIBLE(
        "LIGHT / HFP\nCLASSIC",
        "No neural network · baseline mode compatible with a Bluetooth microphone.",
        ProcessorBackend.CLASSIC_DSP,
        CaptureProfile.PIXEL_SYSTEM,
        VoiceDetectorBackend.SILERO,
        FittingProfile.SPEECH,
        2f, 0.72f, 0.50f, 4f, false, false,
    ),
}

class MainActivity : ComponentActivity() {
    private lateinit var engine: AudioEngine
    private lateinit var sessionRepository: SessionRepository
    private var settings = ProcessorSettings()

    private lateinit var startButton: Button
    private lateinit var logButton: Button
    private lateinit var quickOutcomeRow: LinearLayout
    private lateinit var modeToggle: ToggleButton
    private lateinit var monitorStatusText: TextView
    private lateinit var profileBadge: TextView
    private lateinit var routeText: TextView
    private lateinit var routeWarningText: TextView
    private lateinit var dspDetailText: TextView
    private lateinit var impactDetailText: TextView
    private lateinit var inputSpinner: Spinner
    private lateinit var inputDetailText: TextView
    private lateinit var captureDetailText: TextView
    private lateinit var refreshInputsButton: Button
    private lateinit var waveformView: RealtimeWaveformView
    private lateinit var backendDetailText: TextView
    private lateinit var signalPathText: TextView
    private lateinit var gainValue: TextView
    private lateinit var denoiseValue: TextView
    private lateinit var clarityValue: TextView
    private lateinit var quietSpeechBoostValue: TextView
    private lateinit var presetDetailText: TextView
    private lateinit var gainSlider: SeekBar
    private lateinit var denoiseSlider: SeekBar
    private lateinit var claritySlider: SeekBar
    private lateinit var quietSpeechBoostSlider: SeekBar
    private lateinit var systemNoiseSwitch: Switch
    private lateinit var systemAgcSwitch: Switch
    private lateinit var scoreText: TextView
    private lateinit var logsPanel: LinearLayout
    private lateinit var sessionPlotView: SessionPlotView
    private lateinit var sessionTitleText: TextView
    private lateinit var sessionSummaryText: TextView
    private lateinit var sessionPositionText: TextView
    private val sessionReplayButtons = mutableListOf<Button>()
    private var sessionPlayer: MediaPlayer? = null

    private val metricValues = mutableMapOf<MonitorSeries, TextView>()
    private val metricCards = mutableMapOf<MonitorSeries, LinearLayout>()
    private val seriesButtons = mutableMapOf<MonitorSeries, TextView>()
    private val backendButtons = mutableMapOf<ProcessorBackend, TextView>()
    private val captureButtons = mutableMapOf<CaptureProfile, TextView>()
    private val vadButtons = mutableMapOf<VoiceDetectorBackend, TextView>()
    private val fittingButtons = mutableMapOf<FittingProfile, TextView>()
    private val presetButtons = mutableMapOf<ListeningPreset, TextView>()
    private val sessionPlotButtons = mutableMapOf<SessionPlotMode, TextView>()
    private var selectedSeries = MonitorSeries.INPUT
    private var inputOptions: List<AudioInputOption> = emptyList()
    private var selectedInputKey = AudioEngine.AUTO_INPUT_KEY
    private var understoodCount = 0
    private var missedCount = 0
    private var loggingRequested = false
    private var logSaving = false
    private var logStartedAtEpochMs = 0L
    private var loggedSessions: List<LoggedSession> = emptyList()
    private var selectedSessionIndex = 0
    private var selectedSessionPlotMode = SessionPlotMode.LEVELS
    private var selectedPreset: ListeningPreset? = ListeningPreset.HOME
    private var applyingPreset = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            refreshInputOptions()
            startAudio()
        } else {
            loggingRequested = false
            logStartedAtEpochMs = 0L
            engine.stopSessionLog()
            updateLogButton()
            renderError("Microphone and Nearby devices permissions are required.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        sessionRepository = SessionRepository(this)
        engine = AudioEngine(this, ::renderMetrics, ::onSessionSaved)
        setContentView(buildInterface())
        engine.setDeviceListListener {
            if (!engine.isRunning()) refreshInputOptions()
        }
        refreshInputOptions()
        engine.updateSettings(settings)
        selectCaptureProfile(settings.captureProfile)
        selectBackend(settings.backend)
        selectVoiceDetector(settings.voiceDetectorBackend)
        selectFittingProfile(settings.fittingProfile)
        renderPresetSelection()
        selectMonitorSeries(MonitorSeries.INPUT)
        selectSessionPlotMode(SessionPlotMode.LEVELS)
        refreshLoggedSessions()
        renderMetrics(AudioMetrics(running = false))
    }

    override fun onResume() {
        super.onResume()
        if (::inputSpinner.isInitialized && !engine.isRunning()) refreshInputOptions()
    }

    override fun onDestroy() {
        stopSessionPlayback()
        engine.close()
        super.onDestroy()
    }

    private fun buildInterface(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(0, safe.top, 0, safe.bottom)
            insets
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val horizontalPadding = if (resources.configuration.screenWidthDp >= 600) 28 else 12
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(horizontalPadding), dp(12), dp(horizontalPadding), dp(20))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(buildHeader())
        content.addView(buildMonitorPanel().withTopMargin(10))
        content.addView(buildAdaptiveControlArea().withTopMargin(10))
        content.addView(buildInfoPanel().withTopMargin(10))
        content.addView(buildExperimentPanel().withTopMargin(10))

        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(buildActionBar())
        return root
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titles.addView(text("MICHELINA", 20f, TEXT_PRIMARY, Typeface.BOLD))
        titles.addView(monoText("  AUDIO PROCESSOR", 10f, TEXT_DIM, Typeface.BOLD))
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            monoText("LOCAL", 9f, TEXT_MUTED, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
            },
        )
        return row
    }

    private fun buildMonitorPanel(): View = panel(MONITOR_SURFACE).apply {
        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        monitorStatusText = monoText("● STANDBY", 11f, TEXT_MUTED, Typeface.BOLD)
        profileBadge = monoText("GTCRN · 16K", 9f, TEXT_MUTED, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
        }
        header.addView(
            monitorStatusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(profileBadge)
        addView(header)

        waveformView = RealtimeWaveformView(this@MainActivity).apply {
            setPadding(dp(3), dp(2), dp(3), dp(1))
            background = roundedDrawable(CHART_BACKGROUND, 3f, GRID_STROKE)
        }
        addView(waveformView.withTopMargin(8))
        addView(buildSeriesSelector().withTopMargin(6))
        addView(buildMetricGrid().withTopMargin(6))

        routeWarningText = text("", 12f, WARNING, Typeface.BOLD).apply {
            visibility = View.GONE
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = roundedDrawable(WARNING_DIM, 3f, WARNING_STROKE)
        }
        dspDetailText = monoText("DSP — / — ms · LAT — · XRUN 0 · VAD —", 9f, TEXT_DIM).apply {
            setPadding(0, dp(6), 0, 0)
        }
        impactDetailText = monoText(
            "DENOISE — · CHANGE — · NET —\nFIT 7B — · WEAK —\nVAD —",
            9f,
            TEXT_MUTED,
        ).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = roundedDrawable(CHART_BACKGROUND, 3f, GRID_STROKE)
        }
        addView(routeWarningText.withTopMargin(6))
        addView(dspDetailText)
        addView(impactDetailText.withTopMargin(6))
    }

    private fun buildSeriesSelector(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
        MonitorSeries.entries.forEach { series ->
            val button = monoText(series.shortLabel, 10f, TEXT_MUTED, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                minHeight = dp(32)
                isClickable = true
                isFocusable = true
                setOnClickListener { selectMonitorSeries(series) }
            }
            seriesButtons[series] = button
            addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(1)
                    rightMargin = dp(1)
                },
            )
        }
    }

    private fun buildMetricGrid(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        MonitorSeries.entries.forEach { series ->
            row.addView(metricCard(series), weightedWrap(1f, 2))
        }
        container.addView(row)
        return container
    }

    private fun metricCard(series: MonitorSeries): LinearLayout {
        val label = when (series) {
            MonitorSeries.INPUT -> "IN"
            MonitorSeries.OUTPUT -> "OUT"
            MonitorSeries.VOICE -> "VOICE"
            MonitorSeries.DSP -> "DSP"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(7), dp(6), dp(7), dp(5))
            isClickable = true
            isFocusable = true
            addView(monoText(label, 8f, TEXT_DIM, Typeface.BOLD).apply { letterSpacing = 0.06f })
            val value = monoText("—", 13f, seriesColor(series), Typeface.BOLD).apply {
                setPadding(0, dp(2), 0, 0)
            }
            metricValues[series] = value
            addView(value)
            setOnClickListener { selectMonitorSeries(series) }
            metricCards[series] = this
        }
    }

    private fun buildLogsPanel(): View = panel(SURFACE).also { panel ->
        logsPanel = panel
        panel.visibility = View.GONE

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            monoText("LOGS", 10f, TEXT_PRIMARY, Typeface.BOLD).apply {
                letterSpacing = 0.12f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        sessionPositionText = monoText("0 / 0", 9f, TEXT_DIM, Typeface.BOLD)
        header.addView(sessionPositionText)
        listOf("‹" to -1, "›" to 1).forEach { (label, direction) ->
            header.addView(
                monoText(label, 18f, TEXT_PRIMARY, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(36)
                    minHeight = dp(32)
                    isClickable = true
                    background = roundedDrawable(SURFACE_RAISED, 2f, STROKE)
                    setOnClickListener { moveSession(direction) }
                },
                LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(5)
                },
            )
        }
        panel.addView(header)

        sessionTitleText = monoText("", 11f, TEXT_PRIMARY, Typeface.BOLD).apply {
            setPadding(0, dp(7), 0, dp(5))
        }
        panel.addView(sessionTitleText)
        sessionPlotView = SessionPlotView(this).apply {
            background = roundedDrawable(CHART_BACKGROUND, 3f, GRID_STROKE)
        }
        panel.addView(sessionPlotView)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
        }
        SessionPlotMode.entries.forEach { mode ->
            val button = monoText(mode.label, 9f, TEXT_MUTED, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                minHeight = dp(32)
                isClickable = true
                setOnClickListener { selectSessionPlotMode(mode) }
            }
            sessionPlotButtons[mode] = button
            modeRow.addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        panel.addView(modeRow.withTopMargin(6))
        val replayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            "▶ IN" to "input_raw.wav",
            "▶ OUT" to "output_processed.wav",
            "■ STOP" to "",
        ).forEach { (label, fileName) ->
            val button = Button(this).apply {
                text = label
                textSize = 10f
                isAllCaps = false
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(TEXT_PRIMARY)
                minHeight = dp(36)
                background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
                setOnClickListener {
                    if (fileName.isEmpty()) stopSessionPlayback() else playSessionAudio(fileName)
                }
            }
            sessionReplayButtons += button
            replayRow.addView(button, weightedWrap(1f, 2))
        }
        panel.addView(replayRow.withTopMargin(6))
        sessionSummaryText = monoText("", 9f, TEXT_MUTED).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(0, dp(7), 0, 0)
        }
        panel.addView(sessionSummaryText)
    }

    private fun toggleSessionLog() {
        if (loggingRequested) {
            val wasActive = engine.isSessionLoggingActive()
            loggingRequested = false
            logSaving = wasActive
            engine.stopSessionLog()
            updateLogButton()
            return
        }

        understoodCount = 0
        missedCount = 0
        updateScore()
        loggingRequested = true
        logSaving = false
        logStartedAtEpochMs = System.currentTimeMillis()
        engine.startSessionLog()
        updateLogButton()
        if (!engine.isRunning()) ensurePermissionsAndStart()
    }

    private fun onSessionSaved(session: LoggedSession) {
        loggingRequested = false
        logSaving = false
        logStartedAtEpochMs = 0L
        if (isDestroyed) return
        updateLogButton()
        refreshLoggedSessions(session.id)
    }

    private fun updateLogButton() {
        if (!::logButton.isInitialized) return
        logButton.isEnabled = !logSaving
        logButton.text = when {
            logSaving -> "SAVING…"
            loggingRequested -> "■ ${durationLabel(System.currentTimeMillis() - logStartedAtEpochMs)}"
            else -> "● LOG"
        }
        logButton.setTextColor(
            if (loggingRequested || logSaving) Color.WHITE else TEXT_PRIMARY,
        )
        logButton.background = roundedDrawable(
            if (loggingRequested || logSaving) RECORD_RED else SURFACE_RAISED,
            3f,
            if (loggingRequested || logSaving) RECORD_RED else STROKE,
        )
        if (::quickOutcomeRow.isInitialized) {
            quickOutcomeRow.visibility = if (loggingRequested && !logSaving) View.VISIBLE else View.GONE
        }
    }

    private fun refreshLoggedSessions(preferredId: String? = null) {
        if (!::logsPanel.isInitialized) return
        val previousId = preferredId ?: loggedSessions.getOrNull(selectedSessionIndex)?.id
        loggedSessions = sessionRepository.listSessions()
        logsPanel.visibility = if (loggedSessions.isEmpty()) View.GONE else View.VISIBLE
        if (loggedSessions.isEmpty()) return
        selectedSessionIndex = loggedSessions.indexOfFirst { it.id == previousId }
            .takeIf { it >= 0 }
            ?: 0
        renderSelectedSession()
    }

    private fun moveSession(direction: Int) {
        if (loggedSessions.isEmpty()) return
        stopSessionPlayback()
        selectedSessionIndex = (selectedSessionIndex + direction)
            .coerceIn(0, loggedSessions.lastIndex)
        renderSelectedSession()
    }

    private fun selectSessionPlotMode(mode: SessionPlotMode) {
        selectedSessionPlotMode = mode
        if (::sessionPlotView.isInitialized) sessionPlotView.setMode(mode)
        sessionPlotButtons.forEach { (item, view) ->
            val selected = item == mode
            view.setTextColor(if (selected) BACKGROUND else TEXT_MUTED)
            view.background = roundedDrawable(
                if (selected) sessionPlotColor(item) else Color.TRANSPARENT,
                2f,
            )
        }
    }

    private fun renderSelectedSession() {
        val session = loggedSessions.getOrNull(selectedSessionIndex) ?: return
        val points = sessionRepository.loadMetrics(session.id)
        sessionPlotView.setSession(points)
        sessionPlotView.setMode(selectedSessionPlotMode)
        sessionPositionText.text = "${selectedSessionIndex + 1} / ${loggedSessions.size}"
        val clock = SimpleDateFormat("MMM d · HH:mm", Locale.US)
            .format(Date(session.startedAtEpochMs))
        sessionTitleText.text = "${backendShortLabel(session.backend)}  ·  ${durationLabel(session.durationMs)}  ·  $clock"
        val sizeMb = (session.inputAudioBytes + session.outputAudioBytes) / 1_048_576f
        sessionSummaryText.text = String.format(
            Locale.US,
            "IN %.1f · OUT %.1f · NET %+.1f dB · VOICE %.0f%%\n" +
                "DSP %.2f / %.2f ms · VAD %s %.2f ms · %.0f%% SPEECH\n" +
                "REALTIME %.1f%% · DSP BUDGET %.0f%% · XRUN +%d\n" +
                "SETTINGS %d · A/B %d/%d · %.1f MB",
            session.averageInputDbFs,
            session.averageOutputDbFs,
            session.averageNetDeltaDb,
            session.averageSpeechProbability * 100f,
            session.averageProcessingMs,
            session.peakProcessingMs,
            session.vadModelName,
            session.averageVadInferenceMs,
            session.speechDetectedPercent,
            session.audioRealtimePercent,
            session.dspUtilizationPercent,
            session.underrunDelta,
            session.settingChanges,
            session.understood,
            session.missed,
            sizeMb,
        )
    }

    private fun playSessionAudio(fileName: String) {
        if (engine.isRunning()) return
        val session = loggedSessions.getOrNull(selectedSessionIndex) ?: return
        val audioFile = java.io.File(sessionRepository.directoryFor(session.id), fileName)
        if (!audioFile.isFile) return
        stopSessionPlayback()
        sessionPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(audioFile.absolutePath)
            setOnCompletionListener { stopSessionPlayback() }
            prepare()
            start()
        }
    }

    private fun stopSessionPlayback() {
        val player = sessionPlayer ?: return
        sessionPlayer = null
        runCatching { player.stop() }
        player.reset()
        player.release()
    }

    private fun backendShortLabel(backend: String): String = when (backend) {
        ProcessorBackend.GTCRN_FAST.name -> "GTCRN"
        ProcessorBackend.DPDFNET2_BALANCED.name -> "DPDF2"
        ProcessorBackend.DPDFNET4_STRONG.name -> "DPDF4"
        ProcessorBackend.DPDFNET8_SPEECH.name -> "DPDF8"
        ProcessorBackend.DPDFNET_HQ.name -> "DPDF2 48K"
        ProcessorBackend.RNNOISE_NATIVE.name -> "RNNOISE"
        ProcessorBackend.ULUNAS_STREAM.name -> "UL-UNAS"
        ProcessorBackend.DEEPFILTER3_HQ.name -> "DEEPFILTER3"
        ProcessorBackend.CLASSIC_DSP.name -> "CLASSIC"
        else -> backend
    }

    private fun durationLabel(durationMs: Long): String {
        val totalSeconds = durationMs / 1_000
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun sessionPlotColor(mode: SessionPlotMode): Int = when (mode) {
        SessionPlotMode.LEVELS -> TEXT_PRIMARY
        SessionPlotMode.VOICE -> VOICE_YELLOW
        SessionPlotMode.DSP -> DSP_PURPLE
        SessionPlotMode.IMPACT -> OUTPUT_BLUE
    }

    private fun buildAdaptiveControlArea(): View {
        val wide = resources.configuration.screenWidthDp >= 680
        val container = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        val routePanel = buildRoutePanel()
        val dspPanel = buildDspPanel()
        if (wide) {
            container.addView(
                routePanel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                    rightMargin = dp(7)
                },
            )
            container.addView(
                dspPanel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                    leftMargin = dp(7)
                },
            )
        } else {
            container.addView(routePanel)
            container.addView(dspPanel.withTopMargin(10))
        }
        return container
    }

    private fun buildRoutePanel(): View = panel(SURFACE).apply {
        addView(panelTitle("I/O"))

        inputDetailText = text(
            "",
            10f,
            WARNING,
        ).apply { visibility = View.GONE }
        inputSpinner = Spinner(this@MainActivity).apply {
            minimumHeight = dp(46)
            setPadding(dp(4), 0, dp(4), 0)
            background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val option = inputOptions.getOrNull(position) ?: return
                    selectedInputKey = option.key
                    inputDetailText.text = option.detail
                    inputDetailText.setTextColor(
                        if (option.transport == AudioTransport.BLUETOOTH_HFP) WARNING else TEXT_MUTED,
                    )
                    inputDetailText.visibility = if (
                        option.transport == AudioTransport.BLUETOOTH_HFP
                    ) View.VISIBLE else View.GONE
                    if (!engine.isRunning()) {
                        if (
                            option.transport == AudioTransport.BLUETOOTH_HFP &&
                            settings.backend != ProcessorBackend.CLASSIC_DSP
                        ) {
                            selectBackend(ProcessorBackend.CLASSIC_DSP)
                        }
                        runCatching { engine.selectInput(option.key) }
                            .onFailure { renderError(it.message ?: "Input cannot be selected") }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        refreshInputsButton = Button(this@MainActivity).apply {
            text = "↻"
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT_PRIMARY)
            minWidth = dp(46)
            minHeight = dp(46)
            background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
            setOnClickListener { refreshInputOptions() }
        }
        val inputRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                inputSpinner,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(6)
                },
            )
            addView(refreshInputsButton)
        }
        addView(inputRow.withTopMargin(9))
        addView(inputDetailText.withTopMargin(6))

        addView(buildCaptureSelector().withTopMargin(8))
        captureDetailText = text("", 11f, TEXT_MUTED).apply {
            visibility = View.GONE
        }

        signalPathText = monoText(
            "RAW  /  GTCRN  /  HEADPHONES",
            9f,
            TEXT_MUTED,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        }
        addView(signalPathText)
        routeText = monoText("IN —  /  OUT —", 9f, TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        addView(routeText)
    }

    private fun buildCaptureSelector(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
        val labels = listOf(
            CaptureProfile.RAW to "RAW",
            CaptureProfile.PIXEL_SYSTEM to "SYSTEM",
            CaptureProfile.VOICE_RECOGNITION to "SPEECH",
            CaptureProfile.LIVE_PERFORMANCE to "LIVE",
        )
        labels.chunked(2).forEachIndexed { rowIndex, entries ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            entries.forEach { (profile, label) ->
                val button = monoText(label, 10f, TEXT_MUTED, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(34)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (!engine.isRunning()) {
                            clearPresetSelection()
                            selectCaptureProfile(profile)
                        }
                    }
                }
                captureButtons[profile] = button
                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(2)
                        rightMargin = dp(2)
                    },
                )
            }
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (rowIndex > 0) topMargin = dp(1)
                },
            )
        }
    }

    private fun buildDspPanel(): View = panel(SURFACE).apply {
        addView(panelTitle("PROCESSING"))

        addView(buildPresetSelector().withTopMargin(9))
        addView(buildBackendSelector().withTopMargin(9))
        backendDetailText = monoText("", 9f, TEXT_DIM).apply {
            setPadding(0, dp(5), 0, 0)
        }
        addView(backendDetailText)
        addView(buildVadSelector().withTopMargin(7))
        addView(buildFittingSelector().withTopMargin(5))

        modeToggle = ToggleButton(this@MainActivity).apply {
            textOn = "VOICE FOCUS"
            textOff = "BYPASS"
            isChecked = true
            textSize = 11f
            isAllCaps = false
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT_PRIMARY)
            minHeight = dp(40)
            setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
                clearPresetSelection()
                settings = settings.copy(
                    mode = if (enabled) ProcessingMode.VOICE_FOCUS else ProcessingMode.BYPASS,
                )
                updateModeToggleStyle(enabled)
                engine.updateSettings(settings)
            }
        }
        updateModeToggleStyle(true)
        addView(modeToggle.withTopMargin(8))

        val gain = addSlider(
            parent = this,
            title = "GAIN",
            initial = 30,
            maximum = 120,
            valueFormatter = { String.format(Locale.US, "+%.1f dB", it / 10f) },
        ) { progress ->
            clearPresetSelection()
            settings = settings.copy(gainDb = progress / 10f)
            gainValue.text = String.format(Locale.US, "+%.1f dB", progress / 10f)
            engine.updateSettings(settings)
        }
        gainValue = gain.value
        gainSlider = gain.seek

        val denoise = addSlider(
            parent = this,
            title = "DENOISE",
            initial = 100,
            maximum = 100,
            valueFormatter = { "$it%" },
        ) { progress ->
            clearPresetSelection()
            settings = settings.copy(denoiseStrength = progress / 100f)
            denoiseValue.text = "$progress%"
            engine.updateSettings(settings)
        }
        denoiseValue = denoise.value
        denoiseSlider = denoise.seek

        val clarity = addSlider(
            parent = this,
            title = "PRESENCE · 7 BAND",
            initial = 55,
            maximum = 100,
            valueFormatter = {
                String.format(Locale.US, "+%.1f dB", it / 100f * MAX_PRESENCE_BOOST_DB)
            },
        ) { progress ->
            clearPresetSelection()
            settings = settings.copy(clarity = progress / 100f)
            clarityValue.text = String.format(
                Locale.US,
                "+%.1f dB",
                progress / 100f * MAX_PRESENCE_BOOST_DB,
            )
            engine.updateSettings(settings)
        }
        clarityValue = clarity.value
        claritySlider = clarity.seek

        val quietSpeechBoost = addSlider(
            parent = this,
            title = "WEAK VOICE",
            initial = 40,
            maximum = 120,
            valueFormatter = { String.format(Locale.US, "+%.1f dB", it / 10f) },
        ) { progress ->
            clearPresetSelection()
            settings = settings.copy(quietSpeechBoostDb = progress / 10f)
            quietSpeechBoostValue.text = String.format(
                Locale.US,
                "+%.1f dB",
                progress / 10f,
            )
            engine.updateSettings(settings)
        }
        quietSpeechBoostValue = quietSpeechBoost.value
        quietSpeechBoostSlider = quietSpeechBoost.seek
        systemNoiseSwitch = Switch(this@MainActivity).apply {
            text = "NS"
            textSize = 11f
            setTextColor(TEXT_MUTED)
            isChecked = false
            minHeight = dp(40)
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(ACCENT, TEXT_DIM),
            )
            setOnCheckedChangeListener { _, enabled ->
                clearPresetSelection()
                settings = settings.copy(useSystemNoiseSuppressor = enabled)
                engine.updateSettings(settings)
            }
        }
        systemAgcSwitch = Switch(this@MainActivity).apply {
            text = "AGC"
            textSize = 11f
            setTextColor(TEXT_MUTED)
            isChecked = false
            minHeight = dp(40)
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(ACCENT, TEXT_DIM),
            )
            setOnCheckedChangeListener { _, enabled ->
                clearPresetSelection()
                settings = settings.copy(useSystemAutomaticGainControl = enabled)
                engine.updateSettings(settings)
            }
        }
        val systemEffects = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(systemNoiseSwitch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(systemAgcSwitch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(systemEffects.withTopMargin(3))
    }

    private fun buildInfoPanel(): View = panel(SURFACE).apply {
        val content = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val toggle = monoText("SHOW  +", 9f, TEXT_MUTED, Typeface.BOLD)
        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            addView(
                monoText("GUIDE", 10f, TEXT_PRIMARY, Typeface.BOLD).apply {
                    letterSpacing = 0.12f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(toggle)
            setOnClickListener {
                val opening = content.visibility != View.VISIBLE
                content.visibility = if (opening) View.VISIBLE else View.GONE
                toggle.text = if (opening) "HIDE  −" else "SHOW  +"
            }
        }
        addView(header)

        content.addView(
            monoText(
                "MIC 48K → VAD → DENOISE → 7-BAND FIT → WDRC → LIMITER → HEADPHONES",
                9f,
                TEXT_PRIMARY,
                Typeface.BOLD,
            ).apply {
                setPadding(dp(9), dp(8), dp(9), dp(8))
                background = roundedDrawable(CHART_BACKGROUND, 3f, GRID_STROKE)
            }.withTopMargin(9),
        )
        content.addView(
            guideSection(
                "CAPTURE",
                "RAW keeps the signal closest to the microphone and is the baseline for model comparisons. " +
                    "SYSTEM uses Android MIC; SPEECH is optimized for voice recognition; LIVE minimizes " +
                    "latency and output coupling. Compare them without NS/AGC, then enable one effect at a time.",
            ),
        )
        content.addView(
            guideSection(
                "VOICE FOCUS / BYPASS",
                "Voice Focus enables denoise, presence, and weak-voice processing. Bypass keeps gain, compression, " +
                    "and the limiter active; use it as an A/B control to determine whether the model really helps.",
            ),
        )
        content.addView(
            guideSection(
                "DENOISE",
                "Mix between the original and model-cleaned output: 0% original, 100% model. Start at 80–90%. " +
                    "Reduce it if speech sounds metallic or loses consonants.",
            ),
        )
        content.addView(
            guideSection(
                "PRESENCE",
                "Seven-band fitting: NATURAL colors less, SPEECH is the default, and CONSONANTS boosts 3.5–8 kHz. " +
                    "The value is the maximum boost; start at +4–6 dB and reduce it if speech sounds harsh.",
            ),
        )
        content.addView(
            guideSection(
                "WEAK VOICE",
                "Adaptive seven-band compression triggered by Silero or TEN VAD. " +
                    "Start at +3–4 dB, or +6–8 dB for distant speakers. It does not identify the speaker.",
            ),
        )
        content.addView(
            guideSection(
                "GAIN",
                "Final overall boost: it also amplifies noise and artifacts. Adjust it last, usually to 0–3 dB. " +
                    "The limiter prevents digital clipping; it does not measure sound pressure at the ear.",
            ),
        )
        content.addView(
            guideSection(
                "MODELS",
                "FAST / GTCRN · daily use.\n" +
                    "BALANCED / DPDF2 · distant voices and stronger reduction.\n" +
                    "STRONG / DPDF4 · noisy places; monitor LOAD.\n" +
                    "MAX / DPDF8 · aggressive cleanup, easy to OVERLOAD.\n" +
                    "NATURAL 48K / DPDF2 · full-band and very demanding.\n" +
                    "LIGHT / CLASSIC · no neural network, useful with HFP.",
            ),
        )
        content.addView(
            guideSection(
                "ADJUSTMENT ORDER",
                "1  Microphone position\n2  RAW/System\n3  Model\n4  Denoise\n" +
                    "5  Presence\n6  Weak Voice\n7  Gain\n8  NS/AGC, one at a time",
            ),
        )
        content.addView(
            guideSection(
                "PRESET",
                "QUIET HOME · GTCRN\nDISTANT VOICES · DPDF2 + AGC\n" +
                    "NOISY PLACE · DPDF4\nMAX CLEANUP · DPDF8\n" +
                    "NATURAL SOUND · DPDF2 full-band 48K\nLIGHT / HFP · Classic\n" +
                    "Each preset configures every control. Orange identifies a demanding model; " +
                    "when LOAD exceeds 75%, the phone is close to its limit.",
            ),
        )
        content.addView(
            text(
                "COMPARISON · Use the same room and sentences. Create a separate LOG for every model or preset, " +
                    "then compare LOAD, XRUN, and the number of understood sentences.",
                10f,
                WARNING,
                Typeface.BOLD,
            ).apply {
                setPadding(dp(9), dp(8), dp(9), dp(8))
                background = roundedDrawable(WARNING_DIM, 3f, WARNING_STROKE)
            }.withTopMargin(9),
        )
        addView(content)
    }

    private fun guideSection(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(monoText(title, 9f, TEXT_PRIMARY, Typeface.BOLD).apply { letterSpacing = 0.08f })
        addView(text(body, 11f, TEXT_MUTED).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun buildPresetSelector(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            monoText("CHOOSE A SITUATION · CONFIGURE EVERYTHING", 8f, TEXT_DIM, Typeface.BOLD).apply {
                letterSpacing = 0.07f
                setPadding(0, 0, 0, dp(5))
            },
        )
        val grid = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
        }
        ListeningPreset.entries.chunked(2).forEachIndexed { rowIndex, entries ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            entries.forEach { preset ->
                val button = monoText(preset.label, 9f, TEXT_MUTED, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(50)
                    setLineSpacing(dp(2).toFloat(), 1f)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (!engine.isRunning()) applyPreset(preset)
                    }
                }
                presetButtons[preset] = button
                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(1)
                        rightMargin = dp(1)
                    },
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (rowIndex > 0) topMargin = dp(1) },
            )
        }
        addView(grid)
        presetDetailText = text("", 10f, TEXT_MUTED).apply {
            setPadding(dp(9), dp(8), dp(9), dp(8))
            setLineSpacing(dp(1).toFloat(), 1.05f)
            background = roundedDrawable(CHART_BACKGROUND, 3f, GRID_STROKE)
        }
        addView(presetDetailText.withTopMargin(5))
    }

    private fun applyPreset(preset: ListeningPreset) {
        if (engine.isRunning()) return
        applyingPreset = true
        settings = settings.copy(
            backend = preset.backend,
            mode = ProcessingMode.VOICE_FOCUS,
            captureProfile = preset.capture,
            voiceDetectorBackend = preset.vad,
            fittingProfile = preset.fitting,
            gainDb = preset.gainDb,
            denoiseStrength = preset.denoise,
            clarity = preset.clarity,
            quietSpeechBoostDb = preset.weakVoiceDb,
            useSystemNoiseSuppressor = preset.systemNs,
            useSystemAutomaticGainControl = preset.systemAgc,
        )
        selectCaptureProfile(preset.capture)
        selectBackend(preset.backend)
        selectVoiceDetector(preset.vad)
        selectFittingProfile(preset.fitting)
        modeToggle.isChecked = true
        updateModeToggleStyle(true)
        gainSlider.progress = (preset.gainDb * 10f).toInt()
        denoiseSlider.progress = (preset.denoise * 100f).toInt()
        claritySlider.progress = (preset.clarity * 100f).toInt()
        quietSpeechBoostSlider.progress = (preset.weakVoiceDb * 10f).toInt()
        gainValue.text = String.format(Locale.US, "+%.1f dB", preset.gainDb)
        denoiseValue.text = "${(preset.denoise * 100f).toInt()}%"
        clarityValue.text = String.format(
            Locale.US,
            "+%.1f dB",
            preset.clarity * MAX_PRESENCE_BOOST_DB,
        )
        quietSpeechBoostValue.text = String.format(Locale.US, "+%.1f dB", preset.weakVoiceDb)
        systemNoiseSwitch.isChecked = preset.systemNs
        systemAgcSwitch.isChecked = preset.systemAgc
        applyingPreset = false
        selectedPreset = preset
        renderPresetSelection()
        engine.updateSettings(settings)
    }

    private fun clearPresetSelection() {
        if (applyingPreset || selectedPreset == null) return
        selectedPreset = null
        renderPresetSelection()
    }

    private fun renderPresetSelection() {
        presetButtons.forEach { (preset, view) ->
            val selected = preset == selectedPreset
            view.setTextColor(if (selected) BACKGROUND else TEXT_MUTED)
            view.background = roundedDrawable(
                if (selected) presetSelectionColor(preset) else Color.TRANSPARENT,
                2f,
            )
        }
        if (::presetDetailText.isInitialized) {
            val preset = selectedPreset
            presetDetailText.text = if (preset == null) {
                "CUSTOM · one or more parameters were changed manually."
            } else {
                presetSummary(preset)
            }
            presetDetailText.setTextColor(
                if (preset != null && isHeavyPreset(preset)) WARNING else TEXT_MUTED,
            )
        }
    }

    private fun presetSummary(preset: ListeningPreset): String {
        val capture = when (preset.capture) {
            CaptureProfile.RAW -> "RAW"
            CaptureProfile.PIXEL_SYSTEM -> "SYSTEM"
            CaptureProfile.VOICE_RECOGNITION -> "SPEECH"
            CaptureProfile.LIVE_PERFORMANCE -> "LIVE"
        }
        val vad = if (preset.vad == VoiceDetectorBackend.SILERO) "SILERO" else "TEN"
        return String.format(
            Locale.US,
            "%s\n%s · %s · VAD %s · FIT %s\nDENOISE %.0f%% · PRESENCE +%.1f · WEAK +%.1f · GAIN +%.1f · NS %s · AGC %s",
            preset.description,
            backendFriendlyName(preset.backend),
            capture,
            vad,
            preset.fitting.name,
            preset.denoise * 100f,
            preset.clarity * MAX_PRESENCE_BOOST_DB,
            preset.weakVoiceDb,
            preset.gainDb,
            if (preset.systemNs) "ON" else "OFF",
            if (preset.systemAgc) "ON" else "OFF",
        )
    }

    private fun backendFriendlyName(backend: ProcessorBackend): String = when (backend) {
        ProcessorBackend.RNNOISE_NATIVE -> "FULL-BAND · RNNOISE NATIVE"
        ProcessorBackend.ULUNAS_STREAM -> "CHALLENGER · UL-UNAS"
        ProcessorBackend.DEEPFILTER3_HQ -> "LAB QUALITY · DEEPFILTER3"
        ProcessorBackend.GTCRN_FAST -> "FAST · GTCRN"
        ProcessorBackend.DPDFNET2_BALANCED -> "BALANCED · DPDF2"
        ProcessorBackend.DPDFNET4_STRONG -> "STRONG · DPDF4"
        ProcessorBackend.DPDFNET8_SPEECH -> "MAX · DPDF8"
        ProcessorBackend.DPDFNET_HQ -> "NATURAL 48K · DPDF2"
        ProcessorBackend.CLASSIC_DSP -> "LIGHT · CLASSIC"
    }

    private fun isHeavyPreset(preset: ListeningPreset): Boolean = when (preset.backend) {
        ProcessorBackend.DPDFNET4_STRONG,
        ProcessorBackend.DPDFNET8_SPEECH,
        ProcessorBackend.DPDFNET_HQ,
        ProcessorBackend.DEEPFILTER3_HQ -> true
        else -> false
    }

    private fun presetSelectionColor(preset: ListeningPreset): Int =
        if (isHeavyPreset(preset)) WARNING else LIVE_GREEN

    private fun buildBackendSelector(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)

        val labels = listOf(
            ProcessorBackend.GTCRN_FAST to "FAST · GTCRN",
            ProcessorBackend.RNNOISE_NATIVE to "NATIVE · RNNOISE",
            ProcessorBackend.ULUNAS_STREAM to "CHALLENGER · UL-UNAS",
            ProcessorBackend.DEEPFILTER3_HQ to "LAB QUALITY · DEEPFILTER3",
            ProcessorBackend.DPDFNET2_BALANCED to "BALANCED · DPDF2",
            ProcessorBackend.DPDFNET4_STRONG to "STRONG · DPDF4",
            ProcessorBackend.DPDFNET8_SPEECH to "MAX · DPDF8",
            ProcessorBackend.DPDFNET_HQ to "NATURAL 48K · DPDF2",
            ProcessorBackend.CLASSIC_DSP to "LIGHT · CLASSIC",
        )
        labels.chunked(2).forEachIndexed { rowIndex, entries ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            entries.forEach { (backend, label) ->
                val button = monoText(label, 10f, TEXT_MUTED, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(36)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (!engine.isRunning()) {
                            clearPresetSelection()
                            selectBackend(backend)
                        }
                    }
                }
                backendButtons[backend] = button
                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(1)
                        rightMargin = dp(1)
                    },
                )
            }
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (rowIndex > 0) topMargin = dp(1)
                },
            )
        }
    }

    private fun buildVadSelector(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
        listOf(
            VoiceDetectorBackend.SILERO to "VAD · SILERO",
            VoiceDetectorBackend.TEN_VAD to "VAD · TEN",
        ).forEach { (backend, label) ->
            val button = monoText(label, 9f, TEXT_MUTED, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                minHeight = dp(32)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!engine.isRunning()) {
                        clearPresetSelection()
                        selectVoiceDetector(backend)
                    }
                }
            }
            vadButtons[backend] = button
            addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun buildFittingSelector(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
        listOf(
            FittingProfile.NATURAL to "NATURAL",
            FittingProfile.SPEECH to "SPEECH",
            FittingProfile.CONSONANTS to "CONSONANTS",
        ).forEach { (profile, label) ->
            val button = monoText(label, 8f, TEXT_MUTED, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                minHeight = dp(32)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    clearPresetSelection()
                    selectFittingProfile(profile)
                }
            }
            fittingButtons[profile] = button
            addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun buildExperimentPanel(): View = panel(SURFACE).apply {
        addView(panelTitle("A/B TEST"))

        val buttons = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val understood = Button(this@MainActivity).apply {
            text = "UNDERSTOOD"
            isAllCaps = false
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(BACKGROUND)
            minHeight = dp(40)
            background = roundedDrawable(TEXT_PRIMARY, 3f)
            setOnClickListener { recordUnderstanding(understood = true) }
        }
        val missed = Button(this@MainActivity).apply {
            text = "MISSED"
            isAllCaps = false
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT_PRIMARY)
            minHeight = dp(40)
            background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
            setOnClickListener { recordUnderstanding(understood = false) }
        }
        buttons.addView(understood, weightedWrap(1f, 4))
        buttons.addView(missed, weightedWrap(1f, 4))
        addView(buttons.withTopMargin(8))

        scoreText = monoText("0 / 0", 10f, TEXT_MUTED, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        }
        addView(scoreText)

        addView(
            text(
                "START AT LOW VOLUME · STOP IF YOU FEEL DISCOMFORT OR HEAR FEEDBACK",
                9f,
                WARNING,
                Typeface.BOLD,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = roundedDrawable(WARNING_DIM, 3f, WARNING_STROKE)
            }.withTopMargin(8),
        )
    }

    private fun buildActionBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(10))
        background = roundedDrawable(ACTION_SURFACE, 0f, STROKE)
        val actions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        logButton = Button(this@MainActivity).apply {
            text = "● LOG"
            textSize = 12f
            setTextColor(TEXT_PRIMARY)
            isAllCaps = false
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            minHeight = dp(52)
            background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
            setOnClickListener { toggleSessionLog() }
        }
        startButton = Button(this@MainActivity).apply {
            text = "START"
            textSize = 14f
            setTextColor(BACKGROUND)
            isAllCaps = false
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            minHeight = dp(52)
            background = roundedDrawable(TEXT_PRIMARY, 3f)
            setOnClickListener {
                if (engine.isRunning()) stopAudio() else ensurePermissionsAndStart()
            }
        }
        actions.addView(
            logButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.38f).apply {
                rightMargin = dp(6)
            },
        )
        actions.addView(
            startButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.62f),
        )
        addView(actions)
        quickOutcomeRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            val understood = Button(this@MainActivity).apply {
                text = "✓ UNDERSTOOD"
                textSize = 11f
                isAllCaps = false
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(BACKGROUND)
                minHeight = dp(38)
                background = roundedDrawable(TEXT_PRIMARY, 3f)
                setOnClickListener { recordUnderstanding(understood = true) }
            }
            val missed = Button(this@MainActivity).apply {
                text = "× MISSED"
                textSize = 11f
                isAllCaps = false
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(TEXT_PRIMARY)
                minHeight = dp(38)
                background = roundedDrawable(SURFACE_RAISED, 3f, STROKE)
                setOnClickListener { recordUnderstanding(understood = false) }
            }
            addView(understood, weightedWrap(1f, 3))
            addView(missed, weightedWrap(1f, 3))
        }
        addView(quickOutcomeRow.withTopMargin(6))
    }

    private fun recordUnderstanding(understood: Boolean) {
        if (understood) understoodCount++ else missedCount++
        engine.recordOutcome(understood)
        updateScore()
    }

    private fun panelTitle(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            monoText(title, 10f, TEXT_PRIMARY, Typeface.BOLD).apply {
                letterSpacing = 0.12f
            },
        )
    }

    private fun selectMonitorSeries(series: MonitorSeries) {
        selectedSeries = series
        if (::waveformView.isInitialized) waveformView.setSeries(series)
        seriesButtons.forEach { (item, view) ->
            val selected = item == series
            view.setTextColor(if (selected) BACKGROUND else TEXT_MUTED)
            view.background = roundedDrawable(
                if (selected) seriesColor(item) else Color.TRANSPARENT,
                2f,
            )
        }
        metricCards.forEach { (item, view) ->
            view.background = roundedDrawable(
                if (item == series) selectedCardColor(item) else SURFACE_RAISED,
                2f,
                if (item == series) seriesColor(item) else null,
            )
        }
    }

    private fun selectBackend(backend: ProcessorBackend) {
        if (::engine.isInitialized && engine.isRunning()) return
        settings = settings.copy(backend = backend)
        if (::engine.isInitialized) engine.updateSettings(settings)

        backendButtons.forEach { (item, view) ->
            val selected = item == backend
            view.setTextColor(if (selected) BACKGROUND else TEXT_MUTED)
            view.background = roundedDrawable(
                if (selected) TEXT_PRIMARY else Color.TRANSPARENT,
                2f,
            )
        }
        if (::backendDetailText.isInitialized) {
            backendDetailText.text = when (backend) {
                ProcessorBackend.RNNOISE_NATIVE ->
                    "NATIVE · official RNNoise 0.2 · 48 kHz · 10 ms frame · RNN + DSP"
                ProcessorBackend.ULUNAS_STREAM ->
                    "2026 CHALLENGER · official UL-UNAS · 16 kHz · 770 KB ONNX · stateful"
                ProcessorBackend.DEEPFILTER3_HQ ->
                    "LAB QUALITY · official DeepFilterNet3 · 48 kHz · Tract/Rust · 30 ms latency"
                ProcessorBackend.GTCRN_FAST ->
                    "FAST · daily use · 16 kHz model · 48 kHz I/O · 523 KB"
                ProcessorBackend.DPDFNET2_BALANCED ->
                    "BALANCED · distant voices · 16 kHz model · 9.7 MB"
                ProcessorBackend.DPDFNET4_STRONG ->
                    "STRONG · difficult noise · 16 kHz model · 11.1 MB · monitor LOAD"
                ProcessorBackend.DPDFNET8_SPEECH ->
                    "MAX · quality test · 16 kHz · 13.9 MB · OVERLOAD risk"
                ProcessorBackend.DPDFNET_HQ ->
                    "NATURAL FULL-BAND · 48 kHz · 10.1 MB · OVERLOAD risk"
                ProcessorBackend.CLASSIC_DSP ->
                    "LIGHT · no neural network · 48 kHz · HFP compatible"
            }
        }
        updateSignalPath()
        if (::profileBadge.isInitialized && !engine.isRunning()) {
            profileBadge.text = when (backend) {
                ProcessorBackend.RNNOISE_NATIVE -> "RNNOISE · 48K"
                ProcessorBackend.ULUNAS_STREAM -> "UL-UNAS · 16K"
                ProcessorBackend.DEEPFILTER3_HQ -> "DEEPFILTER3 · 48K"
                ProcessorBackend.GTCRN_FAST -> "GTCRN · 16K"
                ProcessorBackend.DPDFNET2_BALANCED -> "DPDF2 · 16K"
                ProcessorBackend.DPDFNET4_STRONG -> "DPDF4 · 16K"
                ProcessorBackend.DPDFNET8_SPEECH -> "DPDF8 · 16K"
                ProcessorBackend.DPDFNET_HQ -> "DPDF2 · 48K"
                ProcessorBackend.CLASSIC_DSP -> "CLASSIC · 48K"
            }
        }
    }

    private fun selectCaptureProfile(profile: CaptureProfile) {
        if (::engine.isInitialized && engine.isRunning()) return
        settings = settings.copy(captureProfile = profile)
        if (::engine.isInitialized) engine.updateSettings(settings)
        captureButtons.forEach { (item, view) ->
            val selected = item == profile
            view.setTextColor(if (selected) BACKGROUND else TEXT_MUTED)
            view.background = roundedDrawable(
                if (selected) TEXT_PRIMARY else Color.TRANSPARENT,
                2f,
            )
        }
        if (::captureDetailText.isInitialized) {
            captureDetailText.text = when (profile) {
                CaptureProfile.RAW -> "RAW"
                CaptureProfile.PIXEL_SYSTEM -> "PIXEL SYSTEM"
                CaptureProfile.VOICE_RECOGNITION -> "VOICE RECOGNITION"
                CaptureProfile.LIVE_PERFORMANCE -> "LIVE PERFORMANCE"
            }
        }
        updateSignalPath()
    }

    private fun selectVoiceDetector(backend: VoiceDetectorBackend) {
        if (::engine.isInitialized && engine.isRunning()) return
        settings = settings.copy(voiceDetectorBackend = backend)
        if (::engine.isInitialized) engine.updateSettings(settings)
        vadButtons.forEach { (item, view) ->
            val selected = item == backend
            view.setTextColor(if (selected) BACKGROUND else TEXT_MUTED)
            view.background = roundedDrawable(
                if (selected) ACCENT else Color.TRANSPARENT,
                2f,
            )
        }
    }

    private fun selectFittingProfile(profile: FittingProfile) {
        settings = settings.copy(fittingProfile = profile)
        if (::engine.isInitialized) engine.updateSettings(settings)
        fittingButtons.forEach { (item, view) ->
            val selected = item == profile
            view.setTextColor(if (selected) BACKGROUND else TEXT_MUTED)
            view.background = roundedDrawable(
                if (selected) VOICE_YELLOW else Color.TRANSPARENT,
                2f,
            )
        }
    }

    private fun updateSignalPath() {
        if (!::signalPathText.isInitialized) return
        val capture = when (settings.captureProfile) {
            CaptureProfile.RAW -> "RAW"
            CaptureProfile.PIXEL_SYSTEM -> "SYSTEM"
            CaptureProfile.VOICE_RECOGNITION -> "SPEECH"
            CaptureProfile.LIVE_PERFORMANCE -> "LIVE"
        }
        val processor = when (settings.backend) {
            ProcessorBackend.RNNOISE_NATIVE -> "RNNOISE"
            ProcessorBackend.ULUNAS_STREAM -> "UL-UNAS"
            ProcessorBackend.DEEPFILTER3_HQ -> "DEEPFILTER3"
            ProcessorBackend.GTCRN_FAST -> "GTCRN"
            ProcessorBackend.DPDFNET2_BALANCED -> "DPDF2"
            ProcessorBackend.DPDFNET4_STRONG -> "DPDF4"
            ProcessorBackend.DPDFNET8_SPEECH -> "DPDF8"
            ProcessorBackend.DPDFNET_HQ -> "DPDFNET"
            ProcessorBackend.CLASSIC_DSP -> "DSP"
        }
        signalPathText.text = "$capture  /  $processor  /  HEADPHONES"
    }

    private fun refreshInputOptions() {
        if (!::inputSpinner.isInitialized || engine.isRunning()) return
        val previousKey = selectedInputKey
        val latest = engine.availableInputOptions()
        inputOptions = latest
        inputSpinner.adapter = DeviceAdapter(latest.map { it.label })

        val selectedIndex = latest.indexOfFirst { it.key == previousKey }
            .takeIf { it >= 0 }
            ?: 0
        val selected = latest[selectedIndex]
        selectedInputKey = selected.key
        inputSpinner.setSelection(selectedIndex)
        inputDetailText.text = selected.detail
        inputDetailText.setTextColor(
            if (selected.transport == AudioTransport.BLUETOOTH_HFP) WARNING else TEXT_MUTED,
        )
        inputDetailText.visibility = if (
            selected.transport == AudioTransport.BLUETOOTH_HFP
        ) View.VISIBLE else View.GONE
        if (
            selected.transport == AudioTransport.BLUETOOTH_HFP &&
            settings.backend != ProcessorBackend.CLASSIC_DSP
        ) {
            selectBackend(ProcessorBackend.CLASSIC_DSP)
        }
        engine.selectInput(selected.key)
    }

    private fun ensurePermissionsAndStart() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAudio() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startAudio() {
        stopSessionPlayback()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        monitorStatusText.text = "● STARTING"
        monitorStatusText.setTextColor(WARNING)
        startButton.isEnabled = false
        engine.updateSettings(settings)
        engine.start()
    }

    private fun stopAudio() {
        if (loggingRequested || engine.isSessionLoggingActive()) {
            loggingRequested = false
            logSaving = engine.isSessionLoggingActive()
            engine.stopSessionLog()
            updateLogButton()
        }
        engine.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun renderMetrics(metrics: AudioMetrics) {
        waveformView.update(metrics)
        if (metrics.error != null) {
            renderError(metrics.error)
            return
        }

        val running = metrics.running
        if (loggingRequested) updateLogButton()
        startButton.isEnabled = true
        inputSpinner.isEnabled = !running
        refreshInputsButton.isEnabled = !running
        backendButtons.values.forEach {
            it.isEnabled = !running
            it.alpha = if (running) 0.72f else 1f
        }
        captureButtons.values.forEach {
            it.isEnabled = !running
            it.alpha = if (running) 0.72f else 1f
        }
        vadButtons.values.forEach {
            it.isEnabled = !running
            it.alpha = if (running) 0.72f else 1f
        }
        presetButtons.values.forEach {
            it.isEnabled = !running
            it.alpha = if (running) 0.72f else 1f
        }
        sessionReplayButtons.forEach {
            it.isEnabled = !running
            it.alpha = if (running) 0.45f else 1f
        }
        startButton.text = if (running) "STOP" else "START"
        startButton.setTextColor(if (running) TEXT_PRIMARY else BACKGROUND)
        startButton.background = roundedDrawable(
            if (running) STOP_DIM else TEXT_PRIMARY,
            3f,
            if (running) STOP else null,
        )

        monitorStatusText.text = when {
            !running -> "● STANDBY"
            metrics.processingBudgetPercent >= 95f -> "● OVERLOAD · CHANGE MODEL"
            metrics.processingBudgetPercent >= 75f -> "● LIVE · HOT"
            metrics.transport == AudioTransport.BLUETOOTH_HFP -> "● LIVE · HFP"
            else -> "● LIVE · A2DP"
        }
        monitorStatusText.setTextColor(
            when {
                !running -> TEXT_MUTED
                metrics.processingBudgetPercent >= 95f -> STOP
                metrics.processingBudgetPercent >= 75f -> WARNING
                else -> LIVE_GREEN
            },
        )
        profileBadge.text = if (running) {
            val processor = when {
                metrics.transport == AudioTransport.BLUETOOTH_HFP -> "HFP · CLASSIC"
                metrics.backend == ProcessorBackend.RNNOISE_NATIVE -> "RNNOISE NATIVE"
                metrics.backend == ProcessorBackend.ULUNAS_STREAM -> "UL-UNAS STREAM"
                metrics.backend == ProcessorBackend.DEEPFILTER3_HQ -> "DEEPFILTER3 HQ"
                metrics.backend == ProcessorBackend.GTCRN_FAST -> "GTCRN FAST"
                metrics.backend == ProcessorBackend.DPDFNET2_BALANCED -> "DPDFNET2 DAILY"
                metrics.backend == ProcessorBackend.DPDFNET4_STRONG -> "DPDFNET4 STRONG"
                metrics.backend == ProcessorBackend.DPDFNET8_SPEECH -> "DPDFNET8 SPEECH"
                metrics.backend == ProcessorBackend.DPDFNET_HQ -> "DPDFNET HQ"
                else -> "CLASSIC DSP"
            }
            "${metrics.sampleRateHz / 1_000}K · $processor"
        } else {
            when (settings.backend) {
                ProcessorBackend.RNNOISE_NATIVE -> "RNNOISE · 48K"
                ProcessorBackend.ULUNAS_STREAM -> "UL-UNAS · 16K"
                ProcessorBackend.DEEPFILTER3_HQ -> "DEEPFILTER3 · 48K"
                ProcessorBackend.GTCRN_FAST -> "GTCRN · 16K"
                ProcessorBackend.DPDFNET2_BALANCED -> "DPDF2 · 16K"
                ProcessorBackend.DPDFNET4_STRONG -> "DPDF4 · 16K"
                ProcessorBackend.DPDFNET8_SPEECH -> "DPDF8 · 16K"
                ProcessorBackend.DPDFNET_HQ -> "DPDF2 · 48K"
                ProcessorBackend.CLASSIC_DSP -> "CLASSIC · 48K"
            }
        }

        metricValues[MonitorSeries.INPUT]?.text = if (running) {
            String.format(Locale.US, "%.0f dB", metrics.inputDbFs)
        } else "—"
        metricValues[MonitorSeries.OUTPUT]?.text = if (running) {
            String.format(Locale.US, "%.0f dB", metrics.outputDbFs)
        } else "—"
        metricValues[MonitorSeries.VOICE]?.text = if (running) {
            String.format(Locale.US, "%.0f%%", metrics.speechProbability * 100f)
        } else "—"
        metricValues[MonitorSeries.DSP]?.text = if (running) {
            String.format(Locale.US, "%.2f ms", metrics.averageProcessingMs)
        } else "—"

        routeText.text = if (running) {
            "IN ${metrics.inputRoute}  /  OUT ${metrics.outputRoute}"
        } else {
            "IN —  /  OUT —"
        }
        routeWarningText.text = metrics.routeWarning.orEmpty()
        routeWarningText.visibility = if (metrics.routeWarning.isNullOrBlank()) View.GONE else View.VISIBLE
        dspDetailText.text = if (running) {
            String.format(
                Locale.US,
            "DSP %.2f / %.2f ms · LOAD %.0f%% · VAD %.2f · LAT %.1f · XRUN %d",
                metrics.averageProcessingMs,
                metrics.peakProcessingMs,
                metrics.processingBudgetPercent,
                metrics.vadInferenceMs,
                metrics.algorithmLatencyMs,
                metrics.underruns,
            )
        } else {
            "DSP — / — ms · LAT — · XRUN 0"
        }
        impactDetailText.text = if (running) {
            String.format(
                Locale.US,
                "DENOISE %+.1f dB · CHANGE %.0f%% · NET %+.1f dB\n" +
                    "FIT 7B %+.1f · WEAK %+.1f · VOICE %.0f%%\n" +
                    "VAD %s · %s · RAW %.0f%% · %,d WINDOWS\n" +
                    "NS %s · AGC %s · %,d DSP FRAMES",
                metrics.denoiseDeltaDb,
                metrics.signalChangedPercent,
                metrics.netOutputDeltaDb,
                metrics.presenceDeltaDb,
                metrics.effectiveQuietSpeechBoostDb,
                metrics.speechProbability * 100f,
                metrics.vadModelName,
                if (metrics.vadSpeechDetected) "SPEECH" else "NO SPEECH",
                metrics.vadRawProbability * 100f,
                metrics.vadProcessedWindows,
                effectStatus(
                    available = metrics.systemNoiseSuppressorAvailable,
                    enabled = metrics.systemNoiseSuppressorEnabled,
                    requested = settings.useSystemNoiseSuppressor,
                ),
                effectStatus(
                    available = metrics.systemAutomaticGainControlAvailable,
                    enabled = metrics.systemAutomaticGainControlEnabled,
                    requested = settings.useSystemAutomaticGainControl,
                ),
                metrics.processedFrames,
            )
        } else {
            "DENOISE — · CHANGE — · NET —\nFIT 7B — · WEAK —\nVAD —"
        }

        dspDetailText.setTextColor(
            when {
                !running -> TEXT_DIM
                metrics.processingBudgetPercent >= 80f -> STOP
                metrics.processingBudgetPercent >= 60f -> WARNING
                else -> TEXT_DIM
            },
        )

        if (!running) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun renderError(message: String) {
        loggingRequested = false
        logSaving = false
        logStartedAtEpochMs = 0L
        engine.stopSessionLog()
        updateLogButton()
        monitorStatusText.text = "● ROUTE ERROR"
        monitorStatusText.setTextColor(STOP)
        routeText.text = message
        routeWarningText.visibility = View.GONE
        dspDetailText.text = "PIPELINE STOPPED"
        inputSpinner.isEnabled = true
        refreshInputsButton.isEnabled = true
        backendButtons.values.forEach {
            it.isEnabled = true
            it.alpha = 1f
        }
        captureButtons.values.forEach {
            it.isEnabled = true
            it.alpha = 1f
        }
        vadButtons.values.forEach {
            it.isEnabled = true
            it.alpha = 1f
        }
        presetButtons.values.forEach {
            it.isEnabled = true
            it.alpha = 1f
        }
        impactDetailText.text = "ENGINE STOPPED · no frames processed"
        startButton.isEnabled = true
        startButton.text = "RETRY"
        startButton.setTextColor(BACKGROUND)
        startButton.background = roundedDrawable(TEXT_PRIMARY, 3f)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateModeToggleStyle(enabled: Boolean) {
        if (!::modeToggle.isInitialized) return
        modeToggle.background = roundedDrawable(
            if (enabled) TEXT_PRIMARY else SURFACE_RAISED,
            3f,
            if (enabled) TEXT_PRIMARY else STROKE,
        )
        modeToggle.setTextColor(if (enabled) BACKGROUND else TEXT_MUTED)
    }

    private fun effectStatus(
        available: Boolean,
        enabled: Boolean,
        requested: Boolean,
    ): String = when {
        enabled -> "ON"
        !available -> "N/A"
        requested -> "ERR"
        else -> "OFF"
    }

    private fun updateScore() {
        scoreText.text = "$understoodCount / $missedCount"
    }

    private fun addSlider(
        parent: LinearLayout,
        title: String,
        initial: Int,
        maximum: Int,
        valueFormatter: (Int) -> String,
        onChanged: (Int) -> Unit,
    ): SliderViews {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = monoText(title, 10f, TEXT_DIM, Typeface.BOLD).apply {
            letterSpacing = 0.06f
        }
        val valueView = monoText(valueFormatter(initial), 11f, TEXT_PRIMARY, Typeface.BOLD).apply {
            gravity = Gravity.END
        }
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(valueView)
        parent.addView(header.withTopMargin(10))

        val seekBar = SeekBar(this).apply {
            max = maximum
            progress = initial
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(STROKE)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            minHeight = dp(30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChanged(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        parent.addView(seekBar)
        return SliderViews(seekBar, valueView)
    }

    private fun panel(color: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(11), dp(12), dp(12))
        background = roundedDrawable(color, 4f, STROKE)
    }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL,
    ): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, style)
        setLineSpacing(0f, 1.12f)
    }

    private fun monoText(
        value: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL,
    ): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, style)
        setLineSpacing(0f, 1.08f)
    }

    private fun roundedDrawable(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        strokeColor?.let { setStroke(dp(strokeWidthDp), it) }
    }

    private fun View.withTopMargin(topDp: Int): View = apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(topDp) }
    }

    private fun weightedWrap(weight: Float, horizontalMarginDp: Int) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            leftMargin = dp(horizontalMarginDp)
            rightMargin = dp(horizontalMarginDp)
        }

    private fun seriesColor(series: MonitorSeries): Int = when (series) {
        MonitorSeries.INPUT -> ACCENT
        MonitorSeries.OUTPUT -> OUTPUT_BLUE
        MonitorSeries.VOICE -> VOICE_YELLOW
        MonitorSeries.DSP -> DSP_PURPLE
    }

    private fun selectedCardColor(series: MonitorSeries): Int = when (series) {
        MonitorSeries.INPUT -> Color.rgb(30, 30, 30)
        MonitorSeries.OUTPUT -> Color.rgb(12, 27, 34)
        MonitorSeries.VOICE -> Color.rgb(32, 27, 14)
        MonitorSeries.DSP -> Color.rgb(27, 20, 34)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private inner class DeviceAdapter(values: List<String>) : ArrayAdapter<String>(
        this,
        android.R.layout.simple_spinner_dropdown_item,
        values,
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getView(position, convertView, parent) as TextView, dropdown = false)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getDropDownView(position, convertView, parent) as TextView, dropdown = true)

        private fun style(view: TextView, dropdown: Boolean): TextView = view.apply {
            setTextColor(TEXT_PRIMARY)
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(if (dropdown) ACTION_SURFACE else Color.TRANSPARENT)
        }
    }

    private data class SliderViews(val seek: SeekBar, val value: TextView)

    companion object {
        private val BACKGROUND = Color.rgb(4, 4, 4)
        private val SURFACE = Color.rgb(11, 11, 11)
        private val MONITOR_SURFACE = Color.rgb(8, 8, 8)
        private val SURFACE_RAISED = Color.rgb(20, 20, 20)
        private val ACTION_SURFACE = Color.rgb(8, 8, 8)
        private val CHART_BACKGROUND = Color.rgb(3, 3, 3)
        private val TEXT_PRIMARY = Color.rgb(242, 242, 239)
        private val TEXT_MUTED = Color.rgb(158, 158, 154)
        private val TEXT_DIM = Color.rgb(96, 96, 93)
        private val ACCENT = Color.rgb(242, 242, 239)
        private val LIVE_GREEN = Color.rgb(84, 230, 145)
        private val OUTPUT_BLUE = Color.rgb(82, 205, 255)
        private val VOICE_YELLOW = Color.rgb(255, 205, 87)
        private val DSP_PURPLE = Color.rgb(188, 135, 255)
        private val STROKE = Color.rgb(42, 42, 42)
        private val GRID_STROKE = Color.rgb(36, 36, 36)
        private val WARNING = Color.rgb(255, 190, 84)
        private val WARNING_DIM = Color.rgb(52, 38, 13)
        private val WARNING_STROKE = Color.rgb(111, 78, 23)
        private val RECORD_RED = Color.rgb(196, 48, 52)
        private val STOP = Color.rgb(255, 105, 105)
        private val STOP_DIM = Color.rgb(58, 23, 23)
    }
}
