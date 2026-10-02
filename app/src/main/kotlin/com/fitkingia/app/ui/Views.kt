package com.fitkingia.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Quebra os filhos em linhas (chips de alternativas). */
class FlowLayout(context: Context) : ViewGroup(context) {
    private val hGap = context.dp(8)
    private val vGap = context.dp(8)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        var x = 0
        var y = 0
        var rowH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(if (unbounded) 0 else maxWidth, if (unbounded) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (!unbounded && x > 0 && x + c.measuredWidth > maxWidth) {
                x = 0; y += rowH + vGap; rowH = 0
            }
            x += c.measuredWidth + hGap
            widest = max(widest, x - hGap)
            rowH = max(rowH, c.measuredHeight)
        }
        val w = if (unbounded) widest + paddingLeft + paddingRight else MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxWidth) {
                x = 0; y += rowH + vGap; rowH = 0
            }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = max(rowH, c.measuredHeight)
        }
    }
}

/**
 * Gráfico de linha simples: série principal (pontos) e, opcional, série suavizada
 * (ex.: média móvel do peso). Rótulos do primeiro/último ponto e do mínimo/máximo.
 */
class LineChart(
    context: Context,
    private val labels: List<String>,
    private val values: List<Double>,
    private val smooth: List<Double>? = null,
    private val color: Int = C.accent,
    private val format: (Double) -> String = { com.fitkingia.core.model.Fmt.num(it) },
) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(2).toFloat(); this.color = this@LineChart.color }
    private val smoothPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(3).toFloat(); this.color = C.fact }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@LineChart.color }
    private val grid = Paint().apply { this.color = C.stroke; strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = C.muted
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(170))
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val pad = context.dp(8).toFloat()
        val top = context.dp(16).toFloat()
        val bottom = height - context.dp(22).toFloat()
        val left = context.dp(40).toFloat()
        val right = width - pad
        val all = values + smooth.orEmpty()
        var lo = all.minOrNull()!!
        var hi = all.maxOrNull()!!
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val span = hi - lo
        lo -= span * 0.1; hi += span * 0.1
        fun x(i: Int) = if (values.size == 1) (left + right) / 2 else left + (right - left) * i / (values.size - 1)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2
            canvas.drawLine(left, y(v), right, y(v), grid)
            canvas.drawText(format(v), 0f, y(v) + textPaint.textSize / 3, textPaint)
        }
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        canvas.drawPath(path, line)
        smooth?.let { s ->
            val p2 = Path()
            s.forEachIndexed { i, v -> if (i == 0) p2.moveTo(x(i), y(v)) else p2.lineTo(x(i), y(v)) }
            canvas.drawPath(p2, smoothPaint)
        }
        values.forEachIndexed { i, v -> canvas.drawCircle(x(i), y(v), context.dp(3).toFloat(), dot) }
        if (labels.isNotEmpty()) {
            canvas.drawText(labels.first(), left, height - context.dp(4).toFloat(), textPaint)
            if (labels.size > 1) {
                val last = labels.last()
                canvas.drawText(last, right - textPaint.measureText(last), height - context.dp(4).toFloat(), textPaint)
            }
        }
    }
}

/** Barras verticais pequenas (ex.: água nos últimos 7 dias). */
class BarChart(context: Context, private val labels: List<String>, private val values: List<Double>, private val goal: Double? = null) : View(context) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.fact }
    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.success }
    private val goalPaint = Paint().apply { color = C.warning; strokeWidth = context.dp(1).toFloat() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.muted
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(120))
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val bottom = height - context.dp(18).toFloat()
        val top = context.dp(6).toFloat()
        val hi = maxOf(values.maxOrNull() ?: 0.0, goal ?: 0.0, 1.0)
        val slot = width.toFloat() / values.size
        values.forEachIndexed { i, v ->
            val h = ((bottom - top) * (v / hi)).toFloat()
            val cx = slot * i + slot / 2
            val w = slot * 0.55f
            canvas.drawRoundRect(cx - w / 2, bottom - h, cx + w / 2, bottom, 6f, 6f, if (goal != null && v >= goal) okPaint else barPaint)
            canvas.drawText(labels.getOrElse(i) { "" }, cx, height - context.dp(4).toFloat(), textPaint)
        }
        goal?.let {
            val gy = (bottom - (bottom - top) * (it / hi)).toFloat()
            canvas.drawLine(0f, gy, width.toFloat(), gy, goalPaint)
        }
    }
}
