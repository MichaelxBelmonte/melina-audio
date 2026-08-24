package it.michelina.focus.desktop

import it.michelina.focus.audio.ProcessingMode
import it.michelina.focus.audio.ProcessorBackend
import it.michelina.focus.audio.ProcessorSettings
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    Locale.setDefault(Locale.ITALIAN)
    val options = runCatching { DesktopOptions.parse(arguments) }.getOrElse {
        System.err.println("Errore: ${it.message}")
        System.err.println(DesktopOptions.usage())
        exitProcess(2)
    }
    if (options.help) {
        println(DesktopOptions.usage())
        return
    }
    if (options.listDevices) {
        printDevices()
        return
    }
    if (options.gui) {
        System.setProperty("apple.awt.application.name", "Melina")
        SwingUtilities.invokeLater(DesktopWindow::showWindow)
        return
    }

    val input = runCatching { DesktopAudioDevices.findInput(options.input) }.getOrElse {
        System.err.println("Ingresso non valido: ${it.message}")
        exitProcess(2)
    }
    val output = runCatching { DesktopAudioDevices.findOutput(options.output) }.getOrElse {
        System.err.println("Uscita non valida: ${it.message}")
        exitProcess(2)
    }
    val settings = ProcessorSettings(
        backend = options.backend,
        mode = if (options.bypass) ProcessingMode.BYPASS else ProcessingMode.VOICE_FOCUS,
        gainDb = options.gainDb,
        denoiseStrength = options.denoiseStrength,
        clarity = options.clarity,
        quietSpeechBoostDb = options.quietSpeechBoostDb,
    )
    val stopped = CountDownLatch(1)
    val lastReportAt = AtomicLong(0)
    val engine = DesktopAudioEngine(
        inputDevice = input,
        outputDevice = output,
        initialSettings = settings,
        onMetrics = { metrics ->
            val now = System.nanoTime()
            val previous = lastReportAt.get()
            if (now - previous >= TimeUnit.SECONDS.toNanos(1) && lastReportAt.compareAndSet(previous, now)) {
                println(
                    "in %6.1f dBFS · out %6.1f dBFS · voce %3.0f%% · frame %,d".format(
                        metrics.inputDbFs,
                        metrics.outputDbFs,
                        metrics.speechProbability * 100f,
                        metrics.processedFrames,
                    ),
                )
            }
        },
        onError = {
            System.err.println("Errore audio: ${it.message ?: it.javaClass.simpleName}")
            stopped.countDown()
        },
        processorFactory = { DesktopProcessorFactory.create(options.backend) },
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        engine.close()
        stopped.countDown()
    })

    println("Melina Desktop")
    println("Ingresso: ${input.displayName}")
    println("Uscita:   ${output.displayName}")
    println("Backend:  ${options.backend.displayName()} · I/O 48 kHz mono")
    println("Usa cuffie per evitare feedback. Premi Ctrl+C per terminare.")
    try {
        engine.start()
        if (options.durationSeconds == null) {
            while (engine.isRunning()) stopped.await(1, TimeUnit.SECONDS)
        } else {
            stopped.await(options.durationSeconds, TimeUnit.SECONDS)
        }
    } catch (error: Throwable) {
        System.err.println("Impossibile avviare l’audio: ${error.message ?: error.javaClass.simpleName}")
        exitProcess(1)
    } finally {
        engine.close()
    }
}

private fun printDevices() {
    println("Ingressi 48 kHz mono PCM16:")
    println("  default-input\tIngresso predefinito")
    DesktopAudioDevices.inputs().forEach { println("  ${it.id}\t${it.displayName}") }
    println("\nUscite 48 kHz mono PCM16:")
    println("  default-output\tUscita predefinita")
    DesktopAudioDevices.outputs().forEach { println("  ${it.id}\t${it.displayName}") }
}

private data class DesktopOptions(
    val input: String? = null,
    val output: String? = null,
    val backend: ProcessorBackend = ProcessorBackend.CLASSIC_DSP,
    val gainDb: Float = 3f,
    val denoiseStrength: Float = 1f,
    val clarity: Float = 0.55f,
    val quietSpeechBoostDb: Float = 4f,
    val bypass: Boolean = false,
    val durationSeconds: Long? = null,
    val listDevices: Boolean = false,
    val help: Boolean = false,
    val gui: Boolean = true,
) {
    companion object {
        fun parse(arguments: Array<String>): DesktopOptions {
            var result = DesktopOptions(gui = arguments.isEmpty())
            var index = 0
            fun value(name: String): String {
                require(index + 1 < arguments.size) { "Manca il valore per $name" }
                return arguments[++index]
            }
            while (index < arguments.size) {
                result = when (val argument = arguments[index]) {
                    "--input" -> result.copy(input = value(argument))
                    "--output" -> result.copy(output = value(argument))
                    "--backend" -> result.copy(backend = parseBackend(value(argument)))
                    "--gain" -> result.copy(gainDb = value(argument).toFloat().also {
                        require(it in 0f..12f) { "--gain deve essere fra 0 e 12 dB" }
                    })
                    "--denoise" -> result.copy(denoiseStrength = value(argument).toFloat().also {
                        require(it in 0f..1f) { "--denoise deve essere fra 0 e 1" }
                    })
                    "--clarity" -> result.copy(clarity = value(argument).toFloat().also {
                        require(it in 0f..1f) { "--clarity deve essere fra 0 e 1" }
                    })
                    "--quiet-boost" -> result.copy(quietSpeechBoostDb = value(argument).toFloat().also {
                        require(it in 0f..12f) { "--quiet-boost deve essere fra 0 e 12 dB" }
                    })
                    "--duration" -> result.copy(durationSeconds = value(argument).toLong().also {
                        require(it > 0) { "--duration deve essere positivo" }
                    })
                    "--bypass" -> result.copy(bypass = true)
                    "--list-devices" -> result.copy(listDevices = true)
                    "--gui" -> result.copy(gui = true)
                    "--cli" -> result.copy(gui = false)
                    "-h", "--help" -> result.copy(help = true)
                    else -> error("Opzione sconosciuta: $argument")
                }
                index++
            }
            return result
        }

        fun usage(): String = """
            Uso: melina [opzioni]

              --list-devices          Elenca microfoni e uscite compatibili
              --gui                   Apre l’interfaccia grafica (predefinita senza opzioni)
              --cli                   Avvia l’elaborazione nel terminale
              --input <id|nome>       Ingresso (predefinito: sistema)
              --output <id|nome>      Uscita (predefinito: sistema)
              --backend <nome>        classic, rnnoise, ulunas, deepfilter, gtcrn, dpdfnet2, dpdfnet4, dpdfnet8, dpdfnet-hq
              --gain <0..12>          Guadagno software in dB
              --denoise <0..1>        Intensità riduzione rumore
              --clarity <0..1>        Presenza consonanti
              --quiet-boost <0..12>   Incremento parlato debole in dB
              --bypass                Disattiva focus voce, mantiene gain/limiter
              --duration <secondi>    Termina automaticamente
              -h, --help              Mostra questa guida
        """.trimIndent()

        private fun parseBackend(value: String): ProcessorBackend {
            val backend = when (value.lowercase().replace('_', '-')) {
                "classic", "classic-dsp" -> ProcessorBackend.CLASSIC_DSP
                "rnnoise", "rnnoise-native" -> ProcessorBackend.RNNOISE_NATIVE
                "ulunas", "ul-unas", "ulunas-stream" -> ProcessorBackend.ULUNAS_STREAM
                "deepfilter", "deepfilternet3", "deepfilter3" -> ProcessorBackend.DEEPFILTER3_HQ
                "gtcrn", "gtcrn-fast" -> ProcessorBackend.GTCRN_FAST
                "dpdfnet2", "dpdfnet2-balanced" -> ProcessorBackend.DPDFNET2_BALANCED
                "dpdfnet4", "dpdfnet4-strong" -> ProcessorBackend.DPDFNET4_STRONG
                "dpdfnet8", "dpdfnet8-speech" -> ProcessorBackend.DPDFNET8_SPEECH
                "dpdfnet-hq", "dpdfnet2-hq" -> ProcessorBackend.DPDFNET_HQ
                else -> error("Backend sconosciuto: $value")
            }
            require(backend in DesktopProcessorFactory.supportedBackends)
            return backend
        }
    }
}
