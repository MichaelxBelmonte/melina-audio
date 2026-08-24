package it.michelina.focus.audio

import android.content.Context

/**
 * Keeps Android audio I/O at the common native 48 kHz rate while a 16 kHz neural model runs on
 * internally resampled frames. This avoids asking AudioRecord and AudioTrack for a non-native
 * hardware rate and gives every media route a consistent 48 kHz contract.
 */
internal class NativeRateNeuralSpeechEnhancer(
    context: Context,
    backend: ProcessorBackend,
    voiceDetectorBackend: VoiceDetectorBackend,
) : RealtimeAudioProcessor {
    private val model: RealtimeAudioProcessor = when (backend) {
        ProcessorBackend.ULUNAS_STREAM ->
            UlunasSpeechEnhancer(context, voiceDetectorBackend)
        else -> NeuralSpeechEnhancer(context, backend, voiceDetectorBackend)
    }
    private val delegate = NativeRateAudioProcessor(model)

    override val frameSizeSamples: Int
        get() = delegate.frameSizeSamples
    override val algorithmLatencySamples: Int
        get() = delegate.algorithmLatencySamples

    override fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics = delegate.process(input, output, settings)

    override fun close() = delegate.close()
}
