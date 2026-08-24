package it.michelina.focus.desktop

import it.michelina.focus.audio.FrameProcessingMetrics
import it.michelina.focus.audio.ProcessorSettings
import it.michelina.focus.audio.RealtimeAudioProcessor
import it.michelina.focus.audio.SpectralSpeechEnhancer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

class DesktopAudioEngine(
    private val inputDevice: DesktopAudioDevice,
    private val outputDevice: DesktopAudioDevice,
    initialSettings: ProcessorSettings,
    private val onMetrics: (FrameProcessingMetrics) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val processorFactory: () -> RealtimeAudioProcessor = {
        SpectralSpeechEnhancer(sampleRate = DesktopAudioDevices.SAMPLE_RATE)
    },
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val settings = AtomicReference(initialSettings)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var inputLine: TargetDataLine? = null

    @Volatile
    private var outputLine: SourceDataLine? = null

    fun updateSettings(value: ProcessorSettings) {
        settings.set(value)
    }

    fun isRunning(): Boolean = running.get()

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        var processor: RealtimeAudioProcessor? = null
        try {
            processor = processorFactory()
            val frameBytes = processor.frameSizeSamples * BYTES_PER_SAMPLE
            val bufferBytes = frameBytes * BUFFERED_FRAMES
            val input = DesktopAudioDevices.openInput(inputDevice, bufferBytes)
            val output = try {
                DesktopAudioDevices.openOutput(outputDevice, bufferBytes)
            } catch (error: Throwable) {
                input.close()
                throw error
            }
            inputLine = input
            outputLine = output
            output.start()
            input.start()
            worker = Thread({ runLoop(input, output, processor) }, "MichelinaDesktopAudio").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            running.set(false)
            processor?.close()
            releaseLines()
            throw error
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false) && inputLine == null && outputLine == null) return
        runCatching { inputLine?.stop() }
        runCatching { inputLine?.flush() }
        runCatching { inputLine?.close() }
        worker?.interrupt()
        runCatching { worker?.join(1_000) }
        worker = null
        runCatching { outputLine?.drain() }
        releaseLines()
    }

    private fun runLoop(
        inputLine: TargetDataLine,
        outputLine: SourceDataLine,
        processor: RealtimeAudioProcessor,
    ) {
        val samplesPerFrame = processor.frameSizeSamples
        val inputBytes = ByteArray(samplesPerFrame * BYTES_PER_SAMPLE)
        val outputBytes = ByteArray(inputBytes.size)
        val inputSamples = ShortArray(samplesPerFrame)
        val outputSamples = ShortArray(samplesPerFrame)
        try {
            while (running.get()) {
                val bytesRead = readFrame(inputLine, inputBytes)
                if (bytesRead != inputBytes.size) break
                Pcm16.decodeLittleEndian(inputBytes, inputSamples)
                val metrics = processor.process(inputSamples, outputSamples, settings.get())
                Pcm16.encodeLittleEndian(outputSamples, outputBytes)
                writeFrame(outputLine, outputBytes)
                onMetrics(metrics)
            }
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        } finally {
            running.set(false)
            processor.close()
            releaseLines()
        }
    }

    private fun readFrame(line: TargetDataLine, destination: ByteArray): Int {
        var offset = 0
        while (running.get() && offset < destination.size) {
            val count = line.read(destination, offset, destination.size - offset)
            if (count <= 0) break
            offset += count
        }
        return offset
    }

    private fun writeFrame(line: SourceDataLine, source: ByteArray) {
        var offset = 0
        while (running.get() && offset < source.size) {
            val count = line.write(source, offset, source.size - offset)
            if (count <= 0) break
            offset += count
        }
    }

    @Synchronized
    private fun releaseLines() {
        runCatching { inputLine?.close() }
        runCatching { outputLine?.close() }
        inputLine = null
        outputLine = null
    }

    override fun close() = stop()

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFERED_FRAMES = 6
    }
}
