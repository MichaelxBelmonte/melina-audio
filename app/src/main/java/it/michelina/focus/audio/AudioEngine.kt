package it.michelina.focus.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import it.michelina.focus.audio.logging.AudioSessionLogger
import it.michelina.focus.audio.logging.LoggedSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

class AudioEngine(
    context: Context,
    private val onMetrics: (AudioMetrics) -> Unit,
    private val onSessionSaved: (LoggedSession) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settings = AtomicReference(ProcessorSettings())
    private val selectedInputKey = AtomicReference(AUTO_INPUT_KEY)
    private val running = AtomicBoolean(false)
    private val loggingRequested = AtomicBoolean(false)
    private val activeSessionLogger = AtomicReference<AudioSessionLogger?>(null)

    @Volatile
    private var deviceListListener: (() -> Unit)? = null

    @Volatile
    private var ownsCommunicationRoute = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var player: AudioTrack? = null

    @Volatile
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var automaticGainControl: AutomaticGainControl? = null

    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            deviceListListener?.invoke()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            deviceListListener?.invoke()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    fun updateSettings(value: ProcessorSettings) {
        val previous = settings.getAndSet(value)
        if (previous != value) activeSessionLogger.get()?.recordSettings(value)
    }

    fun setDeviceListListener(listener: (() -> Unit)?) {
        deviceListListener = listener
    }

    fun selectInput(key: String) {
        check(!running.get()) { "Stop listening before changing the microphone" }
        selectedInputKey.set(key)
    }

    fun availableInputOptions(): List<AudioInputOption> {
        val options = mutableListOf(
            AudioInputOption(
                key = AUTO_INPUT_KEY,
                label = "Automatic · USB first, then built-in microphone",
                detail = "Recommended: use a USB-C receiver when connected.",
                automatic = true,
            ),
        )

        val inputDevices = getInputDevices()
            .filter { it.type in supportedMediaInputTypes }
            .sortedByDescending(::inputPriority)

        for (device in inputDevices) {
            options += AudioInputOption(
                key = mediaInputKey(device.id),
                label = "${shortDeviceName(device)} · ${inputTypeLabel(device.type)}",
                detail = when (device.type) {
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET ->
                        "External input recommended; output remains on the Bluetooth headphones."
                    AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                        "Built-in microphone: keep the phone exposed, not in a pocket."
                    else -> "Audio input connected to the phone."
                },
                deviceId = device.id,
            )
        }

        val communicationDevices = getCommunicationDevices()
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        for (device in communicationDevices) {
            options += AudioInputOption(
                key = hfpInputKey(device.id),
                label = "${shortDeviceName(device)} · experimental HFP microphone",
                detail = "8 kHz mono: prioritizes the wearer's voice and reduces output quality.",
                deviceId = device.id,
                transport = AudioTransport.BLUETOOTH_HFP,
            )
        }

        return options.distinctBy { it.key }
    }

    @Synchronized
    fun start() {
        if (running.get()) return

        try {
            val input = resolveInputSelection()
            val startSettings = settings.get()
            val requestedBackend = startSettings.backend
            val captureProfile = startSettings.captureProfile
            check(
                input.transport != AudioTransport.BLUETOOTH_HFP ||
                    requestedBackend == ProcessorBackend.CLASSIC_DSP,
            ) {
                "Neural models require Media/USB input and A2DP output. Select Classic DSP for the Ray-Ban HFP microphone."
            }
            configureAudioRouting(input)
            val processingProfile = processingProfile(input.transport, requestedBackend)
            val sampleRate = processingProfile.sampleRate
            val frameSize = processingProfile.frameSize
            val outputDevice = if (input.transport == AudioTransport.MEDIA) {
                checkNotNull(choosePreferredMediaOutput()) {
                    "No safe output found. Connect Bluetooth or wired headphones."
                }
            } else {
                null
            }
            val source = chooseAudioSource(input.transport, captureProfile)

            val record = buildRecorder(source, input.inputDevice, sampleRate, frameSize)
            val track = buildPlayer(outputDevice, sampleRate, frameSize, input.transport)
            val systemNoiseSuppressor = if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(record.audioSessionId)
            } else {
                null
            }
            val systemAutomaticGainControl = if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(record.audioSessionId)
            } else {
                null
            }
            val systemEchoCanceler = if (
                input.transport == AudioTransport.BLUETOOTH_HFP &&
                AcousticEchoCanceler.isAvailable()
            ) {
                AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            } else {
                null
            }

            recorder = record
            player = track
            noiseSuppressor = systemNoiseSuppressor
            automaticGainControl = systemAutomaticGainControl
            echoCanceler = systemEchoCanceler
            running.set(true)

            worker = Thread(
                {
                    runAudioLoop(
                        record = record,
                        track = track,
                        systemNoiseSuppressor = systemNoiseSuppressor,
                        systemAutomaticGainControl = systemAutomaticGainControl,
                        source = source,
                        sampleRate = sampleRate,
                        frameSize = frameSize,
                        resolvedInput = input,
                        backend = requestedBackend,
                        captureProfile = captureProfile,
                    )
                },
                "MichelinaAudio",
            ).apply { start() }
        } catch (error: Throwable) {
            running.set(false)
            loggingRequested.set(false)
            releaseAudioObjects()
            releaseCommunicationRoute()
            publish(
                AudioMetrics(
                    running = false,
                    backend = settings.get().backend,
                    captureProfile = settings.get().captureProfile,
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    @Synchronized
    fun stop() {
        loggingRequested.set(false)
        if (!running.getAndSet(false) && recorder == null && player == null) return

        runCatching { recorder?.stop() }
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        runCatching { player?.stop() }
        worker?.interrupt()
        runCatching { worker?.join(900) }
        worker = null
        releaseAudioObjects()
        releaseCommunicationRoute()
        publish(
            AudioMetrics(
                running = false,
                backend = settings.get().backend,
                captureProfile = settings.get().captureProfile,
            ),
        )
    }

    fun close() {
        stop()
        deviceListListener = null
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    fun isRunning(): Boolean = running.get()

    fun startSessionLog() {
        loggingRequested.set(true)
    }

    fun stopSessionLog() {
        loggingRequested.set(false)
    }

    fun isSessionLogRequested(): Boolean = loggingRequested.get()

    fun isSessionLoggingActive(): Boolean = activeSessionLogger.get() != null

    fun recordOutcome(understood: Boolean) {
        activeSessionLogger.get()?.recordOutcome(understood)
    }

    private fun runAudioLoop(
        record: AudioRecord,
        track: AudioTrack,
        systemNoiseSuppressor: NoiseSuppressor?,
        systemAutomaticGainControl: AutomaticGainControl?,
        source: Int,
        sampleRate: Int,
        frameSize: Int,
        resolvedInput: ResolvedInput,
        backend: ProcessorBackend,
        captureProfile: CaptureProfile,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val inputSamples = ShortArray(frameSize)
        val outputSamples = ShortArray(frameSize)
        var lastPublishTime = 0L
        var averageProcessingMs = 0f
        var peakProcessingMs = 0f
        var lastNoiseSuppressorSetting: Boolean? = null
        var lastAutomaticGainControlSetting: Boolean? = null
        var smoothedDenoiseDeltaDb = 0f
        var smoothedSignalChangedPercent = 0f
        var smoothedPresenceDeltaDb = 0f
        var smoothedQuietSpeechBoostDb = 0f
        var smoothedEffectiveGainDb = 0f
        var smoothedNetOutputDeltaDb = 0f
        var enhancer: RealtimeAudioProcessor? = null
        var sessionLogger: AudioSessionLogger? = null

        try {
            val activeEnhancer = createProcessor(
                backend = backend,
                sampleRate = sampleRate,
                frameSize = frameSize,
                voiceDetectorBackend = settings.get().voiceDetectorBackend,
            )
            enhancer = activeEnhancer
            val warmupSettings = settings.get()
            repeat(PROCESSOR_WARMUP_FRAMES) {
                activeEnhancer.process(inputSamples, outputSamples, warmupSettings)
            }
            inputSamples.fill(0)
            outputSamples.fill(0)
            record.startRecording()
            val primeBuffer = ShortArray(frameSize * OUTPUT_PRIME_FRAMES)
            val primed = track.write(
                primeBuffer,
                0,
                primeBuffer.size,
                AudioTrack.WRITE_BLOCKING,
            )
            check(primed == primeBuffer.size) { "Unable to initialize the audio buffer ($primed)" }
            track.play()
            if (resolvedInput.transport == AudioTransport.BLUETOOTH_HFP) {
                verifyHfpInputRoute(record)
            }

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = readFullFrame(record, inputSamples)
                if (read != frameSize) {
                    if (running.get()) throw IllegalStateException("Audio input interrupted ($read)")
                    break
                }

                val currentSettings = settings.get()
                if (loggingRequested.get() && sessionLogger == null) {
                    sessionLogger = AudioSessionLogger(
                        context = appContext,
                        sampleRateHz = sampleRate,
                        backend = backend,
                        captureProfile = captureProfile,
                        inputRoute = describeDevice(record.routedDevice),
                        outputRoute = describeDevice(track.routedDevice),
                        onFinished = { session ->
                            mainHandler.post { onSessionSaved(session) }
                        },
                    ).also {
                        activeSessionLogger.set(it)
                        it.recordSettings(currentSettings, initial = true)
                    }
                } else if (!loggingRequested.get() && sessionLogger != null) {
                    finishSessionLogger(sessionLogger)
                    sessionLogger = null
                }
                if (currentSettings.useSystemNoiseSuppressor != lastNoiseSuppressorSetting) {
                    runCatching {
                        systemNoiseSuppressor?.enabled = currentSettings.useSystemNoiseSuppressor
                    }
                    lastNoiseSuppressorSetting = currentSettings.useSystemNoiseSuppressor
                }
                if (
                    currentSettings.useSystemAutomaticGainControl !=
                    lastAutomaticGainControlSetting
                ) {
                    runCatching {
                        systemAutomaticGainControl?.enabled =
                            currentSettings.useSystemAutomaticGainControl
                    }
                    lastAutomaticGainControlSetting =
                        currentSettings.useSystemAutomaticGainControl
                }

                val startedAt = System.nanoTime()
                val frameMetrics = activeEnhancer.process(inputSamples, outputSamples, currentSettings)
                sessionLogger?.recordAudio(inputSamples, outputSamples)
                val processingMs = (System.nanoTime() - startedAt) / 1_000_000f
                averageProcessingMs = if (averageProcessingMs == 0f) {
                    processingMs
                } else {
                    0.94f * averageProcessingMs + 0.06f * processingMs
                }
                peakProcessingMs = max(processingMs, peakProcessingMs * 0.995f)
                smoothedDenoiseDeltaDb = smoothMetric(
                    smoothedDenoiseDeltaDb,
                    frameMetrics.denoiseDeltaDb,
                )
                smoothedSignalChangedPercent = smoothMetric(
                    smoothedSignalChangedPercent,
                    frameMetrics.signalChangedPercent,
                )
                smoothedPresenceDeltaDb = smoothMetric(
                    smoothedPresenceDeltaDb,
                    frameMetrics.presenceDeltaDb,
                )
                smoothedQuietSpeechBoostDb = smoothMetric(
                    smoothedQuietSpeechBoostDb,
                    frameMetrics.quietSpeechBoostDb,
                )
                smoothedEffectiveGainDb = smoothMetric(
                    smoothedEffectiveGainDb,
                    frameMetrics.effectiveGainDb,
                )
                smoothedNetOutputDeltaDb = smoothMetric(
                    smoothedNetOutputDeltaDb,
                    frameMetrics.outputDbFs - frameMetrics.inputDbFs,
                )

                val written = track.write(outputSamples, 0, frameSize, AudioTrack.WRITE_BLOCKING)
                if (written < 0 && running.get()) {
                    throw IllegalStateException("Audio output interrupted ($written)")
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastPublishTime >= METRICS_INTERVAL_MS) {
                    lastPublishTime = now
                    val metrics = AudioMetrics(
                            running = true,
                            sampleRateHz = sampleRate,
                            backend = backend,
                            captureProfile = captureProfile,
                            inputDbFs = frameMetrics.inputDbFs,
                            outputDbFs = frameMetrics.outputDbFs,
                            speechProbability = frameMetrics.speechProbability,
                            vadRawProbability = frameMetrics.vadRawProbability,
                            vadSpeechDetected = frameMetrics.vadSpeechDetected,
                            vadProcessedWindows = frameMetrics.vadProcessedWindows,
                            vadInferenceMs = frameMetrics.vadInferenceMs,
                            vadModelName = frameMetrics.vadModelName,
                            processedFrames = frameMetrics.processedFrames,
                            denoiseMixPercent = if (
                                currentSettings.mode == ProcessingMode.VOICE_FOCUS
                            ) {
                                currentSettings.denoiseStrength * 100f
                            } else {
                                0f
                            },
                            denoiseDeltaDb = smoothedDenoiseDeltaDb,
                            signalChangedPercent = smoothedSignalChangedPercent,
                            presenceTargetDb = if (
                                currentSettings.mode == ProcessingMode.VOICE_FOCUS
                            ) {
                                currentSettings.clarity * MAX_PRESENCE_BOOST_DB
                            } else {
                                0f
                            },
                            presenceDeltaDb = smoothedPresenceDeltaDb,
                            requestedQuietSpeechBoostDb = if (
                                currentSettings.mode == ProcessingMode.VOICE_FOCUS
                            ) {
                                currentSettings.quietSpeechBoostDb
                            } else {
                                0f
                            },
                            effectiveQuietSpeechBoostDb = smoothedQuietSpeechBoostDb,
                            requestedGainDb = currentSettings.gainDb,
                            effectiveGainDb = smoothedEffectiveGainDb,
                            netOutputDeltaDb = smoothedNetOutputDeltaDb,
                            systemNoiseSuppressorAvailable = systemNoiseSuppressor != null,
                            systemNoiseSuppressorEnabled = runCatching {
                                systemNoiseSuppressor?.enabled == true
                            }.getOrDefault(false),
                            systemAutomaticGainControlAvailable =
                                systemAutomaticGainControl != null,
                            systemAutomaticGainControlEnabled = runCatching {
                                systemAutomaticGainControl?.enabled == true
                            }.getOrDefault(false),
                            averageProcessingMs = averageProcessingMs,
                            peakProcessingMs = peakProcessingMs,
                            processingBudgetPercent = averageProcessingMs /
                                (frameSize * 1_000f / sampleRate) * 100f,
                            algorithmLatencyMs = algorithmLatencyMs(
                                enhancer = activeEnhancer,
                                backend = backend,
                                mode = currentSettings.mode,
                                sampleRate = sampleRate,
                            ),
                            underruns = track.underrunCount,
                            inputRoute = describeDevice(record.routedDevice),
                            outputRoute = describeDevice(track.routedDevice),
                            sourceDescription = describeSource(source, sampleRate),
                            transport = resolvedInput.transport,
                            routeWarning = routeWarning(
                                requested = resolvedInput,
                                actualInput = record.routedDevice,
                                actualOutput = track.routedDevice,
                            ),
                            inputWaveform = decimateWaveform(inputSamples),
                            outputWaveform = decimateWaveform(outputSamples),
                        )
                    sessionLogger?.recordMetrics(metrics, currentSettings)
                    publish(metrics)
                }
            }
        } catch (error: Throwable) {
            if (running.get()) {
                publish(
                    AudioMetrics(
                        running = false,
                        sampleRateHz = sampleRate,
                        backend = backend,
                        captureProfile = captureProfile,
                        inputRoute = describeDevice(record.routedDevice),
                        outputRoute = describeDevice(track.routedDevice),
                        sourceDescription = describeSource(source, sampleRate),
                        transport = resolvedInput.transport,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
                mainHandler.post { stop() }
            }
        } finally {
            loggingRequested.set(false)
            finishSessionLogger(sessionLogger)
            runCatching { enhancer?.close() }
        }
    }

    private fun finishSessionLogger(logger: AudioSessionLogger?) {
        if (logger == null) return
        activeSessionLogger.compareAndSet(logger, null)
        logger.finishAsync()
    }

    private fun createProcessor(
        backend: ProcessorBackend,
        sampleRate: Int,
        frameSize: Int,
        voiceDetectorBackend: VoiceDetectorBackend,
    ): RealtimeAudioProcessor {
        check(AndroidPlatformCapabilities.supports(backend)) {
            "$backend is not available on Android ABI ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}"
        }
        val processor = when (backend) {
            ProcessorBackend.CLASSIC_DSP -> SpectralSpeechEnhancer(
                sampleRate = sampleRate,
                voiceDetector = NeuralVoiceDetector(appContext, sampleRate, voiceDetectorBackend),
            )
            ProcessorBackend.RNNOISE_NATIVE -> RnnoiseSpeechEnhancer()
            ProcessorBackend.ULUNAS_STREAM ->
                NativeRateNeuralSpeechEnhancer(appContext, backend, voiceDetectorBackend)
            ProcessorBackend.DEEPFILTER3_HQ ->
                DeepFilterSpeechEnhancer(appContext, voiceDetectorBackend)
            ProcessorBackend.GTCRN_FAST,
            ProcessorBackend.DPDFNET2_BALANCED,
            ProcessorBackend.DPDFNET4_STRONG,
            ProcessorBackend.DPDFNET8_SPEECH,
            ProcessorBackend.DPDFNET_HQ -> {
                val spec = neuralModelSpec(backend)
                if (sampleRate == MEDIA_SAMPLE_RATE && spec.sampleRate != MEDIA_SAMPLE_RATE) {
                    NativeRateNeuralSpeechEnhancer(appContext, backend, voiceDetectorBackend)
                } else {
                    NeuralSpeechEnhancer(appContext, backend, voiceDetectorBackend)
                }
            }
        }
        check(processor.frameSizeSamples == frameSize) {
            "Processor frame ${processor.frameSizeSamples}, audio I/O $frameSize"
        }
        return processor
    }

    private fun processingProfile(
        transport: AudioTransport,
        backend: ProcessorBackend,
    ): ProcessingProfile {
        if (transport == AudioTransport.BLUETOOTH_HFP) {
            return ProcessingProfile(HFP_SAMPLE_RATE, SpectralSpeechEnhancer.HOP_SIZE)
        }
        return when (backend) {
            ProcessorBackend.CLASSIC_DSP ->
                ProcessingProfile(MEDIA_SAMPLE_RATE, SpectralSpeechEnhancer.HOP_SIZE)
            ProcessorBackend.RNNOISE_NATIVE ->
                ProcessingProfile(MEDIA_SAMPLE_RATE, RnnoiseSpeechEnhancer.FRAME_SIZE)
            ProcessorBackend.ULUNAS_STREAM ->
                ProcessingProfile(MEDIA_SAMPLE_RATE, UlunasSpeechEnhancer.HOP_SIZE * 3)
            ProcessorBackend.DEEPFILTER3_HQ ->
                ProcessingProfile(MEDIA_SAMPLE_RATE, DeepFilterSpeechEnhancer.FRAME_SIZE)
            ProcessorBackend.GTCRN_FAST,
            ProcessorBackend.DPDFNET2_BALANCED,
            ProcessorBackend.DPDFNET4_STRONG,
            ProcessorBackend.DPDFNET8_SPEECH,
            ProcessorBackend.DPDFNET_HQ -> neuralModelSpec(backend).let {
                if (it.sampleRate == MEDIA_SAMPLE_RATE) {
                    ProcessingProfile(MEDIA_SAMPLE_RATE, it.frameShiftSamples)
                } else {
                    ProcessingProfile(MEDIA_SAMPLE_RATE, it.frameShiftSamples * 3)
                }
            }
        }
    }

    private fun algorithmLatencyMs(
        enhancer: RealtimeAudioProcessor,
        backend: ProcessorBackend,
        mode: ProcessingMode,
        sampleRate: Int,
    ): Float {
        if (backend == ProcessorBackend.CLASSIC_DSP && mode == ProcessingMode.BYPASS) return 0f
        return enhancer.algorithmLatencySamples * 1_000f / sampleRate
    }

    private fun readFullFrame(record: AudioRecord, destination: ShortArray): Int {
        var offset = 0
        while (offset < destination.size && running.get()) {
            val count = record.read(
                destination,
                offset,
                destination.size - offset,
                AudioRecord.READ_BLOCKING,
            )
            if (count <= 0) return count
            offset += count
        }
        return offset
    }

    private fun decimateWaveform(samples: ShortArray): FloatArray {
        val result = FloatArray(WAVEFORM_POINTS)
        for (point in result.indices) {
            val start = point * samples.size / result.size
            val end = ((point + 1) * samples.size / result.size).coerceAtMost(samples.size)
            var signedPeak = 0
            for (index in start until end) {
                val value = samples[index].toInt()
                if (abs(value) > abs(signedPeak)) signedPeak = value
            }
            result[point] = signedPeak / 32_768f
        }
        return result
    }

    private fun buildRecorder(
        source: Int,
        inputDevice: AudioDeviceInfo?,
        sampleRate: Int,
        frameSize: Int,
    ): AudioRecord {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission is not granted")
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "The microphone does not support the required format" }
        val bufferBytes = max(minimum * 2, frameSize * Short.SIZE_BYTES * BUFFERED_FRAMES)
        val record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize the microphone" }
        inputDevice?.let { device ->
            check(record.setPreferredDevice(device)) {
                "Android rejected the ${shortDeviceName(device)} microphone"
            }
        }
        return record
    }

    private fun buildPlayer(
        outputDevice: AudioDeviceInfo?,
        sampleRate: Int,
        frameSize: Int,
        transport: AudioTransport,
    ): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (transport == AudioTransport.BLUETOOTH_HFP) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "The audio output does not support the required format" }
        val bufferBytes = max(minimum * 2, frameSize * Short.SIZE_BYTES * BUFFERED_FRAMES)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Unable to initialize the audio output" }
        outputDevice?.let { device ->
            check(track.setPreferredDevice(device)) {
                "Android rejected the ${shortDeviceName(device)} output"
            }
        }
        return track
    }

    private fun chooseAudioSource(
        transport: AudioTransport,
        captureProfile: CaptureProfile,
    ): Int {
        if (transport == AudioTransport.BLUETOOTH_HFP) {
            return MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        return when (captureProfile) {
            CaptureProfile.PIXEL_SYSTEM -> MediaRecorder.AudioSource.MIC
            CaptureProfile.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            CaptureProfile.LIVE_PERFORMANCE -> MediaRecorder.AudioSource.VOICE_PERFORMANCE
            CaptureProfile.RAW -> {
                val supportsRaw = audioManager.getProperty(
                    AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED,
                ) == "true"
                if (supportsRaw) {
                    MediaRecorder.AudioSource.UNPROCESSED
                } else {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                }
            }
        }
    }

    private fun resolveInputSelection(): ResolvedInput {
        val key = selectedInputKey.get()
        if (key == AUTO_INPUT_KEY) {
            val device = getInputDevices()
                .filter { it.type in supportedMediaInputTypes }
                .maxByOrNull(::inputPriority)
            checkNotNull(device) { "No microphone is available" }
            return ResolvedInput(
                label = "Automatic: ${shortDeviceName(device)}",
                inputDevice = device,
            )
        }

        if (key.startsWith(HFP_INPUT_PREFIX)) {
            val requestedId = key.removePrefix(HFP_INPUT_PREFIX).toIntOrNull()
            val device = getCommunicationDevices().firstOrNull {
                it.id == requestedId && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            checkNotNull(device) {
                "The Ray-Ban HFP microphone is unavailable. Reconnect the glasses and refresh the inputs."
            }
            return ResolvedInput(
                label = "${shortDeviceName(device)} HFP",
                communicationDevice = device,
                transport = AudioTransport.BLUETOOTH_HFP,
            )
        }

        val requestedId = key.removePrefix(MEDIA_INPUT_PREFIX).toIntOrNull()
        val device = getInputDevices().firstOrNull { it.id == requestedId }
        checkNotNull(device) {
            "The selected microphone is no longer connected. Refresh the inputs."
        }
        return ResolvedInput(
            label = shortDeviceName(device),
            inputDevice = device,
        )
    }

    private fun configureAudioRouting(input: ResolvedInput) {
        if (input.transport == AudioTransport.BLUETOOTH_HFP) {
            val communicationDevice = checkNotNull(input.communicationDevice)
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            check(audioManager.setCommunicationDevice(communicationDevice)) {
                "Android cannot activate the Ray-Ban HFP profile"
            }
            ownsCommunicationRoute = true
        } else {
            releaseCommunicationRoute()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun verifyHfpInputRoute(record: AudioRecord) {
        val deadline = SystemClock.elapsedRealtime() + HFP_ROUTE_TIMEOUT_MS
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            val communicationType = audioManager.communicationDevice?.type
            val inputType = record.routedDevice?.type
            if (
                communicationType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                inputType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ) {
                return
            }
            Thread.sleep(HFP_ROUTE_POLL_MS)
        }
        throw IllegalStateException(
            "HFP is not routed: Android is still using ${describeDevice(record.routedDevice)}",
        )
    }

    private fun routeWarning(
        requested: ResolvedInput,
        actualInput: AudioDeviceInfo?,
        actualOutput: AudioDeviceInfo?,
    ): String? {
        if (requested.transport == AudioTransport.BLUETOOTH_HFP) {
            if (actualInput?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return "The requested HFP input is not active; stop the test."
            }
            return "HFP profile: 8 kHz mono, A2DP disabled, and microphones aimed toward the wearer."
        }

        if (requested.inputDevice != null && actualInput?.id != requested.inputDevice.id) {
            return "Android did not honor the requested microphone (${requested.label})."
        }
        if (actualOutput == null || actualOutput.type !in supportedOutputTypes) {
            return "Output not verified: make sure audio is not playing through the phone speaker."
        }
        return null
    }

    private fun getInputDevices(): List<AudioDeviceInfo> =
        runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.getOrDefault(emptyList())

    private fun getCommunicationDevices(): List<AudioDeviceInfo> =
        runCatching { audioManager.availableCommunicationDevices }
            .getOrDefault(emptyList())

    private fun inputPriority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> 100
        AudioDeviceInfo.TYPE_USB_HEADSET -> 95
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 80
        AudioDeviceInfo.TYPE_LINE_ANALOG -> 75
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 50
        else -> 0
    }

    private fun inputTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "digital USB-C"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "headset USB-C"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired microphone"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "analog input"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        else -> "audio input"
    }

    private fun shortDeviceName(device: AudioDeviceInfo): String =
        runCatching { device.productName.toString().trim() }
            .getOrDefault("")
            .ifBlank { inputTypeLabel(device.type) }

    private fun choosePreferredMediaOutput(): AudioDeviceInfo? =
        runCatching {
            val candidates = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in supportedOutputTypes }
            candidates.maxByOrNull { device ->
                val name = shortDeviceName(device).lowercase()
                when {
                    "ray-ban" in name || "rayban" in name || "meta" in name -> 100
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 80
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET -> 75
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> 70
                    else -> 50
                }
            }
        }.getOrNull()

    @Synchronized
    private fun releaseAudioObjects() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { automaticGainControl?.release() }
        runCatching { recorder?.release() }
        runCatching { player?.release() }
        echoCanceler = null
        noiseSuppressor = null
        automaticGainControl = null
        recorder = null
        player = null
    }

    private fun releaseCommunicationRoute() {
        if (!ownsCommunicationRoute) return
        runCatching { audioManager.clearCommunicationDevice() }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        ownsCommunicationRoute = false
    }

    private fun publish(metrics: AudioMetrics) {
        mainHandler.post { onMetrics(metrics) }
    }

    private fun describeDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "system route"
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth HFP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "digital USB"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "headset USB"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "line-in"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            else -> "audio ${device.type}"
        }
        val name = runCatching { device.productName.toString().trim() }.getOrDefault("")
        return if (name.isBlank()) type else "$name · $type"
    }

    private fun describeSource(source: Int, sampleRate: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "Raw input · ${sampleRate / 1_000} kHz"
        MediaRecorder.AudioSource.MIC -> "System microphone input · ${sampleRate / 1_000} kHz"
        MediaRecorder.AudioSource.VOICE_RECOGNITION ->
            "Voice recognition input · ${sampleRate / 1_000} kHz"
        MediaRecorder.AudioSource.VOICE_PERFORMANCE ->
            "Live performance input · ${sampleRate / 1_000} kHz"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "Ray-Ban HFP · 8 kHz mono"
        else -> "source $source"
    }

    private fun smoothMetric(previous: Float, current: Float): Float =
        METRIC_SMOOTHING * previous + (1f - METRIC_SMOOTHING) * current

    companion object {
        const val AUTO_INPUT_KEY = "auto"
        private const val MEDIA_INPUT_PREFIX = "media:"
        private const val HFP_INPUT_PREFIX = "hfp:"
        private const val MEDIA_SAMPLE_RATE = SpectralSpeechEnhancer.SAMPLE_RATE
        private const val HFP_SAMPLE_RATE = 8_000
        private const val BUFFERED_FRAMES = 2
        private const val PROCESSOR_WARMUP_FRAMES = 3
        private const val OUTPUT_PRIME_FRAMES = 2
        private const val METRICS_INTERVAL_MS = 50L
        private const val METRIC_SMOOTHING = 0.86f
        private const val WAVEFORM_POINTS = 96
        private const val HFP_ROUTE_TIMEOUT_MS = 2_500L
        private const val HFP_ROUTE_POLL_MS = 50L

        private fun mediaInputKey(deviceId: Int): String = "$MEDIA_INPUT_PREFIX$deviceId"

        private fun hfpInputKey(deviceId: Int): String = "$HFP_INPUT_PREFIX$deviceId"

        private val supportedMediaInputTypes = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
        )

        private val supportedOutputTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
    }

    private data class ResolvedInput(
        val label: String,
        val inputDevice: AudioDeviceInfo? = null,
        val communicationDevice: AudioDeviceInfo? = null,
        val transport: AudioTransport = AudioTransport.MEDIA,
    )

    private data class ProcessingProfile(
        val sampleRate: Int,
        val frameSize: Int,
    )
}
