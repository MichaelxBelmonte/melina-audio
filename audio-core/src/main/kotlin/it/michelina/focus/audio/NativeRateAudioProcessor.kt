package it.michelina.focus.audio

/** Runs a 16 kHz processor behind a causal 48 kHz I/O contract. */
class NativeRateAudioProcessor(
    private val model: RealtimeAudioProcessor,
) : RealtimeAudioProcessor {
    private val converter = FactorThreeSampleRateConverter(model.frameSizeSamples)
    private val modelInput = ShortArray(model.frameSizeSamples)
    private val modelOutput = ShortArray(model.frameSizeSamples)

    override val frameSizeSamples: Int = model.frameSizeSamples * FACTOR
    override val algorithmLatencySamples: Int =
        model.algorithmLatencySamples * FACTOR + FactorThreeSampleRateConverter.LATENCY_48K_SAMPLES

    override fun process(
        input: ShortArray,
        output: ShortArray,
        settings: ProcessorSettings,
    ): FrameProcessingMetrics {
        require(input.size >= frameSizeSamples && output.size >= frameSizeSamples)
        converter.downsample(input, modelInput)
        val metrics = model.process(modelInput, modelOutput, settings)
        converter.upsample(modelOutput, output)
        return metrics
    }

    override fun close() = model.close()

    companion object {
        private const val FACTOR = 3
    }
}
