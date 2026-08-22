package it.michelina.focus.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import it.michelina.focus.audio.logging.SessionMetricPoint
import kotlin.math.max

enum class SessionPlotMode(val label: String) {
    LEVELS("LEVEL"),
    VOICE("VOICE"),
    DSP("DSP"),
    IMPACT("IMPACT"),
}

class SessionPlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val plot = RectF()
    private val primaryPath = Path()
    private val secondaryPath = Path()
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
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var points: List<SessionMetricPoint> = emptyList()
    private var mode = SessionPlotMode.LEVELS

    init {
        minimumHeight = dp(120f).toInt()
        contentDescription = "Recorded session chart"
    }

    fun setSession(points: List<SessionMetricPoint>) {
        this.points = points
        invalidate()
    }

    fun setMode(mode: SessionPlotMode) {
        this.mode = mode
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(dp(125f).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        plot.set(dp(10f), dp(8f), width - dp(10f), height - dp(10f))
        if (plot.width() <= 1f || plot.height() <= 1f) return
        drawGrid(canvas)
        if (points.size < 2) return
        when (mode) {
            SessionPlotMode.LEVELS -> drawLevels(canvas)
            SessionPlotMode.VOICE -> drawSingle(
                canvas,
                color = VOICE_COLOR,
                value = { it.speechProbability.coerceIn(0f, 1f) },
            )
            SessionPlotMode.DSP -> {
                val scale = points.maxOf { it.processingPeakMs }.coerceAtLeast(4f) * 1.1f
                drawSingle(
                    canvas,
                    color = DSP_COLOR,
                    value = { (it.processingMs / scale).coerceIn(0f, 1f) },
                )
            }
            SessionPlotMode.IMPACT -> drawImpact(canvas)
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
        if (mode == SessionPlotMode.IMPACT) {
            canvas.drawLine(plot.left, plot.centerY(), plot.right, plot.centerY(), axisPaint)
        } else {
            canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axisPaint)
        }
    }

    private fun drawLevels(canvas: Canvas) {
        buildPath(primaryPath) { ((it.inputDbFs + 90f) / 90f).coerceIn(0f, 1f) }
        buildPath(secondaryPath) { ((it.outputDbFs + 90f) / 90f).coerceIn(0f, 1f) }
        drawPath(canvas, secondaryPath, OUTPUT_COLOR)
        drawPath(canvas, primaryPath, INPUT_COLOR)
    }

    private fun drawImpact(canvas: Canvas) {
        var range = 6f
        for (point in points) {
            range = max(range, kotlin.math.abs(point.denoiseDeltaDb))
            range = max(range, kotlin.math.abs(point.netDeltaDb))
        }
        range *= 1.15f
        buildPath(primaryPath) { ((it.denoiseDeltaDb / range) * 0.5f + 0.5f).coerceIn(0f, 1f) }
        buildPath(secondaryPath) { ((it.netDeltaDb / range) * 0.5f + 0.5f).coerceIn(0f, 1f) }
        drawPath(canvas, primaryPath, OUTPUT_COLOR)
        drawPath(canvas, secondaryPath, INPUT_COLOR)
    }

    private fun drawSingle(
        canvas: Canvas,
        color: Int,
        value: (SessionMetricPoint) -> Float,
    ) {
        buildPath(primaryPath, value)
        drawPath(canvas, primaryPath, color)
    }

    private fun buildPath(path: Path, value: (SessionMetricPoint) -> Float) {
        path.reset()
        val stride = (points.size / MAX_DRAW_POINTS).coerceAtLeast(1)
        var drawn = 0
        var sourceIndex = 0
        val drawCount = ((points.size - 1) / stride + 1).coerceAtLeast(2)
        while (sourceIndex < points.size) {
            val x = plot.left + plot.width() * drawn / (drawCount - 1f)
            val y = plot.bottom - value(points[sourceIndex]).coerceIn(0f, 1f) * plot.height()
            if (drawn == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawn++
            sourceIndex += stride
        }
    }

    private fun drawPath(canvas: Canvas, path: Path, color: Int) {
        tracePaint.color = color
        canvas.drawPath(path, tracePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val MAX_DRAW_POINTS = 700
        private val INPUT_COLOR = Color.rgb(242, 242, 239)
        private val OUTPUT_COLOR = Color.rgb(82, 205, 255)
        private val VOICE_COLOR = Color.rgb(255, 205, 87)
        private val DSP_COLOR = Color.rgb(188, 135, 255)
    }
}
