package it.michelina.focus.desktop

import it.michelina.focus.audio.FrameProcessingMetrics
import it.michelina.focus.audio.ProcessorBackend
import it.michelina.focus.audio.ProcessorSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Image
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.ImageIcon
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

class DesktopWindow : JFrame("Melina") {
    private val brandImage: Image? = runCatching {
        ImageIO.read(requireNotNull(javaClass.getResource("/branding/melina-app-icon.png")))
    }.getOrNull()
    private val inputDevices = listOf(DesktopAudioDevices.defaultInput()) + DesktopAudioDevices.inputs()
    private val outputDevices = listOf(DesktopAudioDevices.defaultOutput()) + DesktopAudioDevices.outputs()
    private val inputBox = JComboBox(inputDevices.toTypedArray())
    private val outputBox = JComboBox(outputDevices.toTypedArray())
    private val backendBox = JComboBox(DesktopProcessorFactory.supportedBackends.toTypedArray())
    private val gainSlider = parameterSlider(0, 12, 3)
    private val denoiseSlider = parameterSlider(0, 100, 100)
    private val claritySlider = parameterSlider(0, 100, 55)
    private val quietBoostSlider = parameterSlider(0, 12, 4)
    private val startButton = JButton("AVVIA ASCOLTO")
    private val statusLabel = JLabel("Pronto · collega le cuffie prima di avviare")
    private val inputMeter = JProgressBar(0, 90)
    private val outputMeter = JProgressBar(0, 90)
    private val voiceMeter = JProgressBar(0, 100)
    private val metricsLabel = JLabel("Ingresso — dBFS · Uscita — dBFS · Voce —%")
    private val lastUiUpdate = AtomicLong(0L)

    @Volatile
    private var engine: DesktopAudioEngine? = null

    init {
        brandImage?.let { iconImage = it }
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(680, 590)
        preferredSize = Dimension(760, 650)
        contentPane = buildContent()
        inputBox.renderer = DeviceRenderer()
        outputBox.renderer = DeviceRenderer()
        backendBox.renderer = BackendRenderer()
        startButton.addActionListener { if (engine == null) startAudio() else stopAudio() }
        val updateListener = javax.swing.event.ChangeListener { updateRunningSettings() }
        gainSlider.addChangeListener(updateListener)
        denoiseSlider.addChangeListener(updateListener)
        claritySlider.addChangeListener(updateListener)
        quietBoostSlider.addChangeListener(updateListener)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent?) = stopAudio()
        })
        pack()
        setLocationRelativeTo(null)
    }

    private fun buildContent(): JPanel = JPanel(BorderLayout(20, 20)).apply {
        border = BorderFactory.createEmptyBorder(24, 28, 24, 28)
        background = Color(0xF5F1E8)

        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("MELINA").apply {
                brandImage?.let {
                    icon = ImageIcon(it.getScaledInstance(36, 36, Image.SCALE_SMOOTH))
                    iconTextGap = 10
                }
                font = font.deriveFont(Font.BOLD, 27f)
                foreground = Color(0x21352F)
            })
            add(Box.createVerticalStrut(5))
            add(JLabel("Ascolto assistito in tempo reale · elaborazione locale").apply {
                font = font.deriveFont(14f)
                foreground = Color(0x50615B)
            })
        }, BorderLayout.NORTH)

        add(JPanel(GridBagLayout()).apply {
            isOpaque = false
            val constraints = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                gridx = 0
                gridy = -1
                insets = Insets(6, 0, 6, 0)
            }
            row("Microfono", inputBox, constraints)
            row("Cuffie / uscita", outputBox, constraints)
            row("Modello", backendBox, constraints)
            row("Guadagno", labelledSlider(gainSlider, "dB"), constraints)
            row("Riduzione rumore", labelledSlider(denoiseSlider, "%"), constraints)
            row("Chiarezza", labelledSlider(claritySlider, "%"), constraints)
            row("Parlato debole", labelledSlider(quietBoostSlider, "dB"), constraints)
            row("Livello ingresso", inputMeter, constraints)
            row("Livello uscita", outputMeter, constraints)
            row("Probabilità voce", voiceMeter, constraints)
        }, BorderLayout.CENTER)

        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            metricsLabel.alignmentX = CENTER_ALIGNMENT
            metricsLabel.horizontalAlignment = SwingConstants.CENTER
            add(metricsLabel)
            add(Box.createVerticalStrut(10))
            startButton.alignmentX = CENTER_ALIGNMENT
            startButton.maximumSize = Dimension(Int.MAX_VALUE, 46)
            add(startButton)
            add(Box.createVerticalStrut(10))
            statusLabel.alignmentX = CENTER_ALIGNMENT
            statusLabel.horizontalAlignment = SwingConstants.CENTER
            statusLabel.foreground = Color(0x586A63)
            add(statusLabel)
            add(Box.createVerticalStrut(8))
            add(JLabel("Prototipo di ricerca, non dispositivo medico. Inizia a volume basso.").apply {
                alignmentX = CENTER_ALIGNMENT
                horizontalAlignment = SwingConstants.CENTER
                foreground = Color(0x8B5E3C)
            })
        }, BorderLayout.SOUTH)
    }

    private fun JPanel.row(label: String, component: java.awt.Component, c: GridBagConstraints) {
        c.gridy++
        c.gridx = 0
        c.weightx = 0.28
        add(JLabel(label).apply { font = font.deriveFont(Font.BOLD) }, c)
        c.gridx = 1
        c.weightx = 0.72
        add(component, c)
    }

    private fun labelledSlider(slider: JSlider, suffix: String): JPanel = JPanel(BorderLayout(10, 0)).apply {
        isOpaque = false
        val value = JLabel("${slider.value} $suffix").apply {
            preferredSize = Dimension(52, preferredSize.height)
            horizontalAlignment = SwingConstants.RIGHT
        }
        slider.addChangeListener { value.text = "${slider.value} $suffix" }
        add(slider, BorderLayout.CENTER)
        add(value, BorderLayout.EAST)
    }

    private fun startAudio() {
        val input = inputBox.selectedItem as DesktopAudioDevice
        val output = outputBox.selectedItem as DesktopAudioDevice
        val backend = backendBox.selectedItem as ProcessorBackend
        statusLabel.text = "Caricamento di ${backend.displayName()}…"
        startButton.isEnabled = false
        Thread({
            try {
                val newEngine = DesktopAudioEngine(
                    inputDevice = input,
                    outputDevice = output,
                    initialSettings = currentSettings(backend),
                    onMetrics = ::renderMetrics,
                    onError = ::renderError,
                    processorFactory = { DesktopProcessorFactory.create(backend) },
                )
                newEngine.start()
                check(newEngine.isRunning()) { "Il flusso audio si è interrotto durante l’avvio" }
                engine = newEngine
                SwingUtilities.invokeLater {
                    setSelectorsEnabled(false)
                    startButton.text = "INTERROMPI"
                    startButton.isEnabled = true
                    statusLabel.text = "Ascolto attivo · ${backend.displayName()} · 48 kHz"
                }
            } catch (error: Throwable) {
                renderError(error)
            }
        }, "MichelinaDesktopStart").apply { isDaemon = true }.start()
    }

    private fun stopAudio() {
        val active = engine
        engine = null
        active?.close()
        SwingUtilities.invokeLater {
            setSelectorsEnabled(true)
            startButton.text = "AVVIA ASCOLTO"
            startButton.isEnabled = true
            statusLabel.text = "Pronto · collega le cuffie prima di avviare"
            inputMeter.value = 0
            outputMeter.value = 0
            voiceMeter.value = 0
        }
    }

    private fun updateRunningSettings() {
        val backend = backendBox.selectedItem as? ProcessorBackend ?: return
        engine?.updateSettings(currentSettings(backend))
    }

    private fun currentSettings(backend: ProcessorBackend) = ProcessorSettings(
        backend = backend,
        gainDb = gainSlider.value.toFloat(),
        denoiseStrength = denoiseSlider.value / 100f,
        clarity = claritySlider.value / 100f,
        quietSpeechBoostDb = quietBoostSlider.value.toFloat(),
    )

    private fun renderMetrics(metrics: FrameProcessingMetrics) {
        val now = System.nanoTime()
        val previous = lastUiUpdate.get()
        if (now - previous < TimeUnit.MILLISECONDS.toNanos(80) ||
            !lastUiUpdate.compareAndSet(previous, now)
        ) return
        SwingUtilities.invokeLater {
            inputMeter.value = dbToMeter(metrics.inputDbFs)
            outputMeter.value = dbToMeter(metrics.outputDbFs)
            voiceMeter.value = (metrics.speechProbability * 100f).toInt().coerceIn(0, 100)
            metricsLabel.text = "Ingresso %.1f dBFS · Uscita %.1f dBFS · Voce %.0f%%".format(
                metrics.inputDbFs,
                metrics.outputDbFs,
                metrics.speechProbability * 100f,
            )
        }
    }

    private fun renderError(error: Throwable) {
        val active = engine
        engine = null
        if (Thread.currentThread().name != "MichelinaDesktopAudio") active?.close()
        SwingUtilities.invokeLater {
            setSelectorsEnabled(true)
            startButton.text = "RIPROVA"
            startButton.isEnabled = true
            statusLabel.text = "Errore: ${error.message ?: error.javaClass.simpleName}"
            statusLabel.foreground = Color(0xA63D40)
        }
    }

    private fun setSelectorsEnabled(enabled: Boolean) {
        inputBox.isEnabled = enabled
        outputBox.isEnabled = enabled
        backendBox.isEnabled = enabled
        if (enabled) statusLabel.foreground = Color(0x586A63)
    }

    private fun dbToMeter(db: Float): Int = (db + 90f).toInt().coerceIn(0, 90)

    private class DeviceRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): java.awt.Component = super.getListCellRendererComponent(
            list, (value as? DesktopAudioDevice)?.displayName ?: value,
            index, isSelected, cellHasFocus,
        )
    }

    private class BackendRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): java.awt.Component = super.getListCellRendererComponent(
            list, (value as? ProcessorBackend)?.displayName() ?: value,
            index, isSelected, cellHasFocus,
        )
    }

    companion object {
        fun showWindow() {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            DesktopWindow().isVisible = true
        }

        private fun parameterSlider(minimum: Int, maximum: Int, value: Int) =
            JSlider(minimum, maximum, value).apply { isOpaque = false }
    }
}
