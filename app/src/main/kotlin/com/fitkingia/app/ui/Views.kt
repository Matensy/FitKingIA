package com.fitkingia.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

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

/** Preenchimento da barra de progresso; [shown] (0..1) é o que está desenhado (animado pelo Motion). */
class BarFill(context: Context, val fraction: Float, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    var shown: Float = fraction
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (shown <= 0f || width == 0) return
        val h = height.toFloat()
        // Valor pequeno ainda aparece como um ponto (largura mínima = altura).
        val w = max(width * shown.coerceAtMost(1f), min(h, width.toFloat()))
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, paint)
    }
}

/**
 * Anel do cronômetro de descanso: o arco esvazia continuamente conforme o tempo passa e o
 * tempo restante fica no centro. Nos últimos 10 s o arco fica na cor de destaque.
 */
class RestRing(context: Context) : View(context) {
    private var left = 0
    private var total = 1
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val stroke = context.dp(6).toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = C.stroke }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = C.fact }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.text
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, context.resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val oval = RectF()

    /** Fração desenhada (0..1). */
    val fraction: Float get() = shown

    /**
     * Atualiza o tempo. [smooth] anima o arco até o novo valor (a cada segundo, ~1 s linear, o
     * que dá um movimento contínuo); [fromEmpty] enche do zero (descanso que acabou de começar).
     */
    fun set(left: Int, total: Int, smooth: Boolean = true, fromEmpty: Boolean = false) {
        this.left = left
        this.total = max(max(total, left), 1)
        val target = left.toFloat() / this.total
        contentDescription = "Descanso ${Dates.mmss(left)}"
        arc.color = if (left <= 10) C.accent else C.fact
        anim?.cancel()
        anim = null
        if (!Motion.on(context) || (!smooth && !fromEmpty)) { shown = target; invalidate(); return }
        val from = if (fromEmpty) 0f else shown
        shown = from
        anim = ValueAnimator.ofFloat(from, target).apply {
            duration = if (fromEmpty) 420 else 950
            interpolator = if (fromEmpty) Motion.easeOut else Motion.linear
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = context.dp(58)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - s / 2 + stroke / 2, cy - s / 2 + stroke / 2, cx + s / 2 - stroke / 2, cy + s / 2 - stroke / 2)
        canvas.drawArc(oval, 0f, 360f, false, track)
        if (shown > 0f) canvas.drawArc(oval, -90f, 360f * shown.coerceAtMost(1f), false, arc)
        canvas.drawText(Dates.mmss(left), cx, cy - (label.descent() + label.ascent()) / 2, label)
    }
}

/** Confete simples (partículas em Canvas) para comemorar; [progress] vai de 0 a 1 em ~1,5 s. */
class ConfettiView(context: Context, seed: Long = 7L) : View(context) {
    private class Piece(
        val angle: Double, val speed: Float, val spin: Float, val rot0: Float, val flip: Float,
        val w: Float, val h: Float, val color: Int, val round: Boolean,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieces = ArrayList<Piece>()
    var progress = 0f
        set(v) { field = v; invalidate() }

    init {
        val rnd = java.util.Random(seed)
        val colors = intArrayOf(C.accent, C.success, C.warning, C.fact, C.ai, C.danger, C.text)
        val unit = context.dp(1).toFloat()
        repeat(COUNT) {
            // Leque para cima (de -170° a -10°), alguns pedaços mais rápidos que outros.
            pieces += Piece(
                angle = Math.toRadians(-170.0 + rnd.nextDouble() * 160.0),
                speed = 0.55f + rnd.nextFloat() * 0.75f,
                spin = (rnd.nextFloat() - 0.5f) * 900f,
                rot0 = rnd.nextFloat() * 360f,
                flip = 4f + rnd.nextFloat() * 8f,
                w = unit * (5 + rnd.nextInt(5)),
                h = unit * (8 + rnd.nextInt(6)),
                color = colors[rnd.nextInt(colors.size)],
                round = rnd.nextInt(4) == 0,
            )
        }
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f || progress >= 1f) return
        val t = progress * DURATION_MS / 1000f // segundos
        val ox = width / 2f
        val oy = height * 0.2f
        val g = height * 0.8f // "gravidade" (px/s²)
        val drag = 2.2f       // resistência do ar: o leque abre rápido e desacelera
        val travel = (1 - Math.exp((-drag * t).toDouble())).toFloat() / drag
        val fadeFrom = 0.7f
        val alpha = if (progress < fadeFrom) 255 else ((1 - (progress - fadeFrom) / (1 - fadeFrom)) * 255).toInt().coerceIn(0, 255)
        for (p in pieces) {
            // Abertura horizontal proporcional à largura; vertical, à altura.
            val x = ox + (Math.cos(p.angle) * p.speed * width * travel * 1.1).toFloat()
            val y = oy + (Math.sin(p.angle) * p.speed * height * travel).toFloat() + 0.5f * g * t * t
            paint.color = p.color
            paint.alpha = alpha
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(p.rot0 + p.spin * t)
            // "Vira" no ar: a altura oscila como se o papel girasse em 3D.
            val sy = Math.abs(Math.cos((p.flip * t).toDouble())).toFloat().coerceAtLeast(0.15f)
            if (p.round) canvas.drawCircle(0f, 0f, p.w / 2, paint)
            else canvas.drawRect(-p.w / 2, -p.h / 2 * sy, p.w / 2, p.h / 2 * sy, paint)
            canvas.restore()
        }
    }

    companion object {
        const val DURATION_MS = 1500L
        private const val COUNT = 90
    }
}

/**
 * Ícone de calendário desenhado (aba Semana). Os emojis de calendário (📅, 🗓) aparecem na fonte
 * do Android com uma data em inglês ("July 17"); este não tem texto e segue a paleta do app.
 */
class CalendarIcon : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()).toFloat()
        val left = b.left + (b.width() - s) / 2
        val top = b.top + (b.height() - s) / 2
        val r = s * 0.16f
        rect.set(left + s * 0.06f, top + s * 0.12f, left + s * 0.94f, top + s * 0.96f)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(0xFFE9EDF2.toInt())
        canvas.drawRoundRect(rect, r, r, paint)
        // Faixa do mês (laranja do app) presa ao contorno arredondado.
        canvas.save()
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.clipPath(clip)
        paint.color = withAlpha(C.accent)
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + s * 0.27f, paint)
        canvas.restore()
        // Argolas e grade de dias (sem números).
        paint.color = withAlpha(0xFF4A5361.toInt())
        val ring = s * 0.07f
        for (x in listOf(0.3f, 0.7f)) canvas.drawRoundRect(left + s * x - ring / 2, top, left + s * x + ring / 2, top + s * 0.24f, ring / 2, ring / 2, paint)
        val cell = s * 0.13f
        for (row in 0 until 2) for (col in 0 until 3) {
            val cx = left + s * (0.26f + col * 0.24f)
            val cy = top + s * (0.55f + row * 0.22f)
            canvas.drawRect(cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2, paint)
        }
    }

    private fun withAlpha(color: Int): Int = (color and 0x00FFFFFF) or (((color ushr 24) * alphaValue / 255) shl 24)

    override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Exigido pela API 23")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
