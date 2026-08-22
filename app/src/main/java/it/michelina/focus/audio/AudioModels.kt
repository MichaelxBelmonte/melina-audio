package it.michelina.focus.audio

enum class ProcessingMode {
    BYPASS,
    VOICE_FOCUS,
}

enum class ProcessorBackend {
    CLASSIC_DSP,
    RNNOISE_NATIVE,
    ULUNAS_STREAM,
    DEEPFILTER3_HQ,
    GTCRN_FAST,
    DPDFNET2_BALANCED,
    DPDFNET4_STRONG,
    DPDFNET8_SPEECH,
    DPDFNET_HQ,
}

const val MAX_PRESENCE_BOOST_DB = 10f
const val MAX_QUIET_SPEECH_BOOST_DB = 12f

enum class AudioTransport {
    MEDIA,
    BLUETOOTH_HFP,
}

enum class CaptureProfile {
    RAW,
    PIXEL_SYSTEM,
    VOICE_RECOGNITION,
    LIVE_PERFORMANCE,
}

enum class VoiceDetectorBackend {
    SILERO,
    TEN_VAD,
}

enum class FittingProfile {
    NATURAL,
    SPEECH,
    CONSONANTS,
}

data class AudioInputOption(
    val key: String,
    val label: String,
    val detail: String,
    val deviceId: Int? = null,
    val transport: AudioTransport = AudioTransport.MEDIA,
    val automatic: Boolean = false,
)

data class ProcessorSettings(
    val backend: ProcessorBackend = ProcessorBackend.GTCRN_FAST,
    val mode: ProcessingMode = ProcessingMode.VOICE_FOCUS,
    val captureProfile: CaptureProfile = CaptureProfile.RAW,
    val voiceDetectorBackend: VoiceDetectorBackend = VoiceDetectorBackend.SILERO,
    val fittingProfile: FittingProfile = FittingProfile.SPEECH,
    val gainDb: Float = 3f,
    val denoiseStrength: Float = 1f,
    val clarity: Float = 0.55f,
    val quietSpeechBoostDb: Float = 4f,
    val useSystemNoiseSuppressor: Boolean = false,
    val useSystemAutomaticGainControl: Boolean = false,
)

data class FrameProcessingMetrics(
    val inputDbFs: Float,
    val outputDbFs: Float,
    val speechProbability: Float,
    val vadRawProbability: Float = 0f,
    val vadSpeechDetected: Boolean = false,
    val vadProcessedWindows: Long = 0,
    val vadInferenceMs: Float = 0f,
    val vadModelName: String = "—",
    val processedFrames: Long = 0,
    val denoiseDeltaDb: Float = 0f,
    val signalChangedPercent: Float = 0f,
    val presenceDeltaDb: Float = 0f,
    val quietSpeechBoostDb: Float = 0f,
    val effectiveGainDb: Float = 0f,
)

interface RealtimeAudioProcessor : AutoCloseable {
    val frameSizeSamples: Int
    val algorithmLatencySamples: Int

    fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics

    override fun close() = Unit
}

data class AudioMetrics(
    val running: Boolean,
    val sampleRateHz: Int = 48_000,
    val backend: ProcessorBackend = ProcessorBackend.GTCRN_FAST,
    val captureProfile: CaptureProfile = CaptureProfile.RAW,
    val inputDbFs: Float = -90f,
    val outputDbFs: Float = -90f,
    val speechProbability: Float = 0f,
    val vadRawProbability: Float = 0f,
    val vadSpeechDetected: Boolean = false,
    val vadProcessedWindows: Long = 0,
    val vadInferenceMs: Float = 0f,
    val vadModelName: String = "—",
    val processedFrames: Long = 0,
    val denoiseMixPercent: Float = 0f,
    val denoiseDeltaDb: Float = 0f,
    val signalChangedPercent: Float = 0f,
    val presenceTargetDb: Float = 0f,
    val presenceDeltaDb: Float = 0f,
    val requestedQuietSpeechBoostDb: Float = 0f,
    val effectiveQuietSpeechBoostDb: Float = 0f,
    val requestedGainDb: Float = 0f,
    val effectiveGainDb: Float = 0f,
    val netOutputDeltaDb: Float = 0f,
    val systemNoiseSuppressorAvailable: Boolean = false,
    val systemNoiseSuppressorEnabled: Boolean = false,
    val systemAutomaticGainControlAvailable: Boolean = false,
    val systemAutomaticGainControlEnabled: Boolean = false,
    val averageProcessingMs: Float = 0f,
    val peakProcessingMs: Float = 0f,
    val processingBudgetPercent: Float = 0f,
    val algorithmLatencyMs: Float = 0f,
    val underruns: Int = 0,
    val inputRoute: String = "—",
    val outputRoute: String = "—",
    val sourceDescription: String = "—",
    val transport: AudioTransport = AudioTransport.MEDIA,
    val routeWarning: String? = null,
    val inputWaveform: FloatArray = FloatArray(0),
    val outputWaveform: FloatArray = FloatArray(0),
    val error: String? = null,
)
