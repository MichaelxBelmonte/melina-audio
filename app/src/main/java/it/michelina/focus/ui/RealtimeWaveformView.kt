package it.michelina.focus.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import it.michelina.focus.audio.AudioMetrics
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

enum class MonitorSeries(val shortLabel: String) {
    INPUT("I/O"),
    OUTPUT("OUTPUT"),
    VOICE("VOICE"),
    DSP("DSP"),
}

class RealtimeWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(31, 31, 31)
        strokeWidth = dp(1f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(65, 65, 65)
        strokeWidth = dp(1f)
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(135, 135, 132)
        textSize = sp(9f)
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.08f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(10f)
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE,
            android.graphics.Typeface.BOLD,
        )
        textAlign = Paint.Align.RIGHT
    }

    private val tracePath = Path()
    private val secondaryTracePath = Path()
    private val fillPath = Path()
    private val plot = RectF()
    private var selectedSeries = MonitorSeries.INPUT
    private var inputTarget = FloatArray(0)
    private var outputTarget = FloatArray(0)
    private var displayedInput = FloatArray(0)
    private var displayedOutput = FloatArray(0)
    private val voiceHistory = FloatArray(HISTORY_SIZE)
    private val dspHistory = FloatArray(HISTORY_SIZE)
    private var historyCursor = 0
    private var historyCount = 0
    private var inputDbFs = -90f
    private var outputDbFs = -90f
    private var speechProbability = 0f
    private var processingMs = 0f
    private var waveformScale = 1f
    private var running = false

    init {
        minimumHeight = dp(145f).toInt()
        contentDescription = "Real-time audio monitor"
    }

    fun setSeries(series: MonitorSeries) {
        if (selectedSeries == series) return
        selectedSeries = series
        invalidate()
    }

    fun update(metrics: AudioMetrics) {
        running = metrics.running
        inputTarget = metrics.inputWaveform
        outputTarget = metrics.outputWaveform
        inputDbFs = metrics.inputDbFs
        outputDbFs = metrics.outputDbFs
        speechProbability = metrics.speechProbability
        processingMs = metrics.averageProcessingMs

        if (metrics.running) {
            voiceHistory[historyCursor] = metrics.speechProbability.coerceIn(0f, 1f)
            dspHistory[historyCursor] = metrics.averageProcessingMs.coerceAtLeast(0f)
            historyCursor = (historyCursor + 1) % HISTORY_SIZE
            historyCount = (historyCount + 1).coerceAtMost(HISTORY_SIZE)
        }
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(155f).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        plot.set(
            paddingLeft + dp(12f),
            paddingTop + dp(29f),
            width - paddingRight - dp(12f),
            height - paddingBottom - dp(14f),
        )
        if (plot.width() <= 1f || plot.height() <= 1f) return

        drawGrid(canvas)
        drawHeader(canvas)
        when (selectedSeries) {
            MonitorSeries.INPUT -> drawSignalComparison(canvas)
            MonitorSeries.OUTPUT -> drawWaveform(canvas, outputTarget, isInput = false)
            MonitorSeries.VOICE -> drawHistory(canvas, voiceHistory, 1f)
            MonitorSeries.DSP -> drawHistory(canvas, dspHistory, dspHistoryScale())
        }
    }

    private fun drawGrid(canvas: Canvas) {
        for (index in 0..8) {
            val x = plot.left + plot.width() * index / 8f
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
        }
        for (index in 0..4) {
            val y = plot.top + plot.height() * index / 4f
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
        }
        if (selectedSeries == MonitorSeries.INPUT || selectedSeries == MonitorSeries.OUTPUT) {
            canvas.drawLine(plot.left, plot.centerY(), plot.right, plot.centerY(), axisPaint)
        } else {
            canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axisPaint)
        }
    }

    private fun drawHeader(canvas: Canvas) {
        val accent = seriesColor()
        labelPaint.color = Color.rgb(135, 135, 132)
        canvas.drawText("${selectedSeries.shortLabel} · REALTIME", plot.left, dp(20f), labelPaint)

        valuePaint.color = accent
        val value = when (selectedSeries) {
            MonitorSeries.INPUT -> String.format(
                Locale.US,
                "IN %.0f  OUT %.0f dB",
                inputDbFs,
                outputDbFs,
            )
            MonitorSeries.OUTPUT -> String.format(Locale.US, "%.0f dBFS", outputDbFs)
            MonitorSeries.VOICE -> String.format(Locale.US, "%.0f%%", speechProbability * 100f)
            MonitorSeries.DSP -> String.format(Locale.US, "%.2f ms", processingMs)
        }
        canvas.drawText(value, plot.right, dp(21f), valuePaint)

        fillPaint.color = if (running) LIVE_GREEN else Color.rgb(85, 85, 82)
        canvas.drawCircle(plot.left - dp(7f), dp(17f), dp(2.4f), fillPaint)
    }

    private fun drawSignalComparison(canvas: Canvas) {
        if (inputTarget.isEmpty() && outputTarget.isEmpty()) {
            drawIdleLine(canvas)
            return
        }
        displayedInput = resizeIfNeeded(displayedInput, inputTarget.size)
        displayedOutput = resizeIfNeeded(displayedOutput, outputTarget.size)
        val inputAnimating = animateTowards(inputTarget, displayedInput)
        val outputAnimating = animateTowards(outputTarget, displayedOutput)
        var peak = 0f
        for (value in displayedInput) peak = max(peak, abs(value))
        for (value in displayedOutput) peak = max(peak, abs(value))
        updateWaveformScale(peak)

        buildWaveformPath(displayedInput, tracePath)
        buildWaveformPath(displayedOutput, secondaryTracePath)
        drawTrace(canvas, secondaryTracePath, OUTPUT_COLOR)
        drawTrace(canvas, tracePath, INPUT_COLOR)
        if (inputAnimating || outputAnimating) postInvalidateOnAnimation()
    }

    private fun drawWaveform(canvas: Canvas, target: FloatArray, isInput: Boolean) {
        if (target.isEmpty()) {
            drawIdleLine(canvas)
            return
        }
        var displayed = if (isInput) displayedInput else displayedOutput
        displayed = resizeIfNeeded(displayed, target.size)
        if (isInput) displayedInput = displayed else displayedOutput = displayed
        val stillAnimating = animateTowards(target, displayed)
        var peak = 0f
        for (value in displayed) peak = max(peak, abs(value))
        updateWaveformScale(peak)
        buildWaveformPath(displayed, tracePath)
        drawTrace(canvas, tracePath, if (isInput) INPUT_COLOR else OUTPUT_COLOR)
        if (stillAnimating) postInvalidateOnAnimation()
    }

    private fun resizeIfNeeded(current: FloatArray, size: Int): FloatArray =
        if (current.size == size) current else FloatArray(size)

    private fun animateTowards(target: FloatArray, displayed: FloatArray): Boolean {
        var animating = false
        for (index in target.indices) {
            val difference = target[index] - displayed[index]
            displayed[index] += difference * WAVEFORM_LERP
            if (abs(difference) > 0.0005f) animating = true
        }
        return animating
    }

    private fun updateWaveformScale(peak: Float) {
        val desiredScale = (0.72f / peak.coerceAtLeast(0.025f)).coerceIn(1f, 18f)
        waveformScale = waveformScale * 0.86f + desiredScale * 0.14f
    }

    private fun buildWaveformPath(samples: FloatArray, path: Path) {
        path.reset()
        for (index in samples.indices) {
            val x = plot.left + plot.width() * index / (samples.size - 1).coerceAtLeast(1)
            val normalized = (samples[index] * waveformScale).coerceIn(-1f, 1f)
            val y = plot.centerY() - normalized * plot.height() * 0.46f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    private fun drawHistory(canvas: Canvas, history: FloatArray, scale: Float) {
        if (historyCount < 2) {
            drawIdleLine(canvas)
            return
        }
        tracePath.reset()
        fillPath.reset()
        val start = if (historyCount == HISTORY_SIZE) historyCursor else 0
        for (index in 0 until historyCount) {
            val sourceIndex = (start + index) % HISTORY_SIZE
            val source = if (selectedSeries == MonitorSeries.VOICE) {
                history[sourceIndex]
            } else {
                history[sourceIndex] / scale
            }
            val normalized = source.coerceIn(0f, 1f)
            val x = plot.left + plot.width() * index / (HISTORY_SIZE - 1f)
            val y = plot.bottom - normalized * plot.height() * 0.92f
            if (index == 0) {
                tracePath.moveTo(x, y)
                fillPath.moveTo(x, plot.bottom)
                fillPath.lineTo(x, y)
            } else {
                tracePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        val lastX = plot.left + plot.width() * (historyCount - 1) / (HISTORY_SIZE - 1f)
        fillPath.lineTo(lastX, plot.bottom)
        fillPath.close()

        fillPaint.color = withAlpha(seriesColor(), 32)
        canvas.drawPath(fillPath, fillPaint)
        drawTrace(canvas, tracePath, seriesColor())
    }

    private fun drawIdleLine(canvas: Canvas) {
        tracePath.reset()
        val y = if (
            selectedSeries == MonitorSeries.INPUT || selectedSeries == MonitorSeries.OUTPUT
        ) {
            plot.centerY()
        } else {
            plot.bottom
        }
        tracePath.moveTo(plot.left, y)
        tracePath.lineTo(plot.right, y)
        drawTrace(canvas, tracePath, seriesColor())
    }

    private fun drawTrace(canvas: Canvas, path: Path, color: Int) {
        tracePaint.color = color
        canvas.drawPath(path, tracePaint)
    }

    private fun dspHistoryScale(): Float {
        var maximum = 1f
        for (index in 0 until historyCount) maximum = max(maximum, dspHistory[index])
        return (maximum * 1.25f).coerceAtLeast(4f)
    }

    private fun seriesColor(): Int = when (selectedSeries) {
        MonitorSeries.INPUT -> INPUT_COLOR
        MonitorSeries.OUTPUT -> OUTPUT_COLOR
        MonitorSeries.VOICE -> Color.rgb(255, 205, 87)
        MonitorSeries.DSP -> Color.rgb(188, 135, 255)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    companion object {
        private const val HISTORY_SIZE = 120
        private const val WAVEFORM_LERP = 0.34f
        private val INPUT_COLOR = Color.rgb(242, 242, 239)
        private val OUTPUT_COLOR = Color.rgb(82, 205, 255)
        private val LIVE_GREEN = Color.rgb(84, 230, 145)
    }
}
