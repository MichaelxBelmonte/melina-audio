package it.michelina.focus.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

enum class AudioDeviceDirection {
    INPUT,
    OUTPUT,
}

data class DesktopAudioDevice(
    val id: String,
    val name: String,
    val description: String,
    val direction: AudioDeviceDirection,
    internal val mixerInfo: Mixer.Info?,
) {
    val displayName: String
        get() = if (description.isBlank() || description == name) name else "$name · $description"
}

object DesktopAudioDevices {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val SAMPLE_SIZE_BITS = 16

    val format = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        SAMPLE_RATE.toFloat(),
        SAMPLE_SIZE_BITS,
        CHANNELS,
        CHANNELS * (SAMPLE_SIZE_BITS / 8),
        SAMPLE_RATE.toFloat(),
        false,
    )

    fun inputs(): List<DesktopAudioDevice> = devices(AudioDeviceDirection.INPUT)

    fun outputs(): List<DesktopAudioDevice> = devices(AudioDeviceDirection.OUTPUT)

    fun defaultInput(): DesktopAudioDevice = DesktopAudioDevice(
        id = "default-input",
        name = "Ingresso predefinito",
        description = "Dispositivo di sistema",
        direction = AudioDeviceDirection.INPUT,
        mixerInfo = null,
    )

    fun defaultOutput(): DesktopAudioDevice = DesktopAudioDevice(
        id = "default-output",
        name = "Uscita predefinita",
        description = "Dispositivo di sistema",
        direction = AudioDeviceDirection.OUTPUT,
        mixerInfo = null,
    )

    fun findInput(query: String?): DesktopAudioDevice = find(inputs(), defaultInput(), query)

    fun findOutput(query: String?): DesktopAudioDevice = find(outputs(), defaultOutput(), query)

    internal fun openInput(device: DesktopAudioDevice, bufferBytes: Int): TargetDataLine {
        require(device.direction == AudioDeviceDirection.INPUT)
        val line = if (device.mixerInfo == null) {
            AudioSystem.getTargetDataLine(format)
        } else {
            AudioSystem.getMixer(device.mixerInfo).getLine(
                DataLine.Info(TargetDataLine::class.java, format),
            ) as TargetDataLine
        }
        line.open(format, bufferBytes)
        return line
    }

    internal fun openOutput(device: DesktopAudioDevice, bufferBytes: Int): SourceDataLine {
        require(device.direction == AudioDeviceDirection.OUTPUT)
        val line = if (device.mixerInfo == null) {
            AudioSystem.getSourceDataLine(format)
        } else {
            AudioSystem.getMixer(device.mixerInfo).getLine(
                DataLine.Info(SourceDataLine::class.java, format),
            ) as SourceDataLine
        }
        line.open(format, bufferBytes)
        return line
    }

    private fun devices(direction: AudioDeviceDirection): List<DesktopAudioDevice> =
        AudioSystem.getMixerInfo().mapIndexedNotNull { index, info ->
            val mixer = AudioSystem.getMixer(info)
            val supported = when (direction) {
                AudioDeviceDirection.INPUT -> mixer.isLineSupported(
                    DataLine.Info(TargetDataLine::class.java, format),
                )
                AudioDeviceDirection.OUTPUT -> mixer.isLineSupported(
                    DataLine.Info(SourceDataLine::class.java, format),
                )
            }
            if (!supported) return@mapIndexedNotNull null
            DesktopAudioDevice(
                id = "${direction.name.lowercase()}-$index",
                name = info.name,
                description = info.description,
                direction = direction,
                mixerInfo = info,
            )
        }

    private fun find(
        devices: List<DesktopAudioDevice>,
        default: DesktopAudioDevice,
        query: String?,
    ): DesktopAudioDevice {
        if (query.isNullOrBlank() || query.equals("default", ignoreCase = true)) return default
        val normalized = query.trim()
        devices.firstOrNull { it.id.equals(normalized, ignoreCase = true) }?.let { return it }
        val matches = devices.filter {
            it.name.contains(normalized, ignoreCase = true) ||
                it.description.contains(normalized, ignoreCase = true)
        }
        require(matches.size == 1) {
            if (matches.isEmpty()) {
                "Nessun dispositivo corrisponde a '$query'"
            } else {
                "'$query' è ambiguo: ${matches.joinToString { it.name }}"
            }
        }
        return matches.single()
    }
}
