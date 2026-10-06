package com.fitkingia.app.figure

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import com.fitkingia.app.ui.C
import com.fitkingia.app.ui.dp

/** Adaptador do [FigureCanvas] para o Canvas do Android. */
class AndroidFigureCanvas : FigureCanvas {
    lateinit var canvas: Canvas
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = width
        canvas.drawLine(x1, y1, x2, y2, stroke)
    }

    override fun circle(cx: Float, cy: Float, r: Float, color: Int, stroke: Float) {
        if (stroke > 0f) {
            this.stroke.color = color
            this.stroke.strokeWidth = stroke
            canvas.drawCircle(cx, cy, r, this.stroke)
        } else {
            fill.color = color
            canvas.drawCircle(cx, cy, r, fill)
        }
    }

    override fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        fill.color = color
        rect.set(l, t, r, b)
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) path.lineTo(xs[i], ys[i])
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int) {
        textPaint.color = color
        textPaint.textSize = size
        canvas.drawText(s, x, y, textPaint)
    }

    override fun textWidth(s: String, size: Float): Float {
        textPaint.textSize = size
        return textPaint.measureText(s)
    }
}

/**
 * Ilustração animada de um exercício: a figura repete o movimento em ciclo (≈ 2,4 s, com pausa
 * nos extremos) enquanto está na janela. Tocar pausa/retoma. Com animações desligadas no sistema
 * (acessibilidade), mostra o fim do movimento parado.
 *
 * As telas são reconstruídas inteiras a cada atualização (ex.: cada toque no "+" da carga). Por
 * isso o relógio da animação e a pausa ficam guardados por movimento, fora da View: a figura
 * nova continua do ponto em que a anterior estava, em vez de recomeçar ou "despausar".
 */
class ExerciseFigureView(
    context: Context,
    val motion: Motion,
    private val heightDp: Int = 220,
    /** Nome lido pelo leitor de tela (o do exercício, quando houver). */
    description: String = motion.name,
) : View(context) {
    private val drawer = AndroidFigureCanvas()
    private val bounds = FigurePainter.bounds(motion)
    private val palette = FigurePalette(
        figure = C.text, figureBack = 0xFF8C96A5.toInt(), equipment = C.accent,
        frame = 0xFF5A6472.toInt(), pad = 0xFF6E7A89.toInt(), floor = C.stroke,
        background = C.surface, label = C.muted,
    )
    private var running = false
    private var animations = true
    private var failed = false

    /** Quadro fixo (0 = início, 1 = fim do percurso); null = animando. Usado em testes e capturas. */
    var fixedFrame: Float? = null
        set(value) { field = value; invalidate() }

    val paused: Boolean get() = motion.id in pausedAt

    init {
        contentDescription = "Ilustração animada: $description. Toque para pausar ou continuar."
        isClickable = true
        setOnClickListener { togglePause() }
    }

    private fun readAnimationsEnabled(): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (e: Exception) { true }

    fun togglePause() {
        val now = SystemClock.uptimeMillis()
        val since = pausedAt.remove(motion.id)
        if (since != null) offset[motion.id] = (offset[motion.id] ?: 0L) + (now - since) else pausedAt[motion.id] = now
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else context.dp(heightDp)
        setMeasuredDimension(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        // Lido uma vez por exibição (e não a cada quadro): é uma consulta ao sistema.
        animations = readAnimationsEnabled()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    /** Posição no percurso agora. */
    fun currentFrame(): Float {
        fixedFrame?.let { return it }
        if (!animations) return 1f
        val now = pausedAt[motion.id] ?: SystemClock.uptimeMillis()
        return motion.phase(now - (offset[motion.id] ?: 0L))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || failed) return
        val label = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        try {
            paintFrame(canvas, currentFrame(), w, h, context.dp(10).toFloat(), label)
        } catch (e: RuntimeException) {
            // Uma pose inválida não pode derrubar o treino: some a figura, o resto da tela continua.
            failed = true
            android.util.Log.e("FitKingIA", "falha ao desenhar ${motion.id}", e)
            return
        }
        if (paused && fixedFrame == null && animations) {
            // Ícone de pausa (duas barrinhas) no canto superior direito.
            val x = w - label * 0.8f
            val y = label * 0.5f
            drawer.rect(x - label * 0.95f, y, x - label * 0.6f, y + label * 1.1f, label * 0.12f, C.accent)
            drawer.rect(x - label * 0.35f, y, x, y + label * 1.1f, label * 0.12f, C.accent)
        }
        // Próximo quadro só enquanto estiver na janela, animando e sem pausa.
        if (running && fixedFrame == null && !paused && animations) postInvalidateOnAnimation()
    }

    /** Desenha o quadro [frame] num Canvas qualquer (folhas de contato nos testes). */
    fun drawFrame(canvas: Canvas, frame: Float, w: Float, h: Float) {
        val label = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        paintFrame(canvas, frame, w, h, context.dp(8).toFloat(), label)
    }

    private fun paintFrame(canvas: Canvas, frame: Float, w: Float, h: Float, pad: Float, label: Float) {
        drawer.canvas = canvas
        // Com rótulo da vista ("vista de cima"…), a figura começa abaixo dele em vez de passar por cima.
        val top = if (motion.viewLabel != null) pad + label * 1.2f else pad
        val viewport = FigurePainter.fit(bounds, w, h, pad, top)
        FigurePainter.paint(drawer, motion, motion.pose(frame), viewport, w, palette, label)
    }

    private companion object {
        // Só a thread principal mexe nestes mapas (desenho e toque).
        /** Movimento → instante em que foi pausado. */
        val pausedAt = HashMap<String, Long>()
        /** Movimento → tempo acumulado em pausa (o ciclo continua de onde parou). */
        val offset = HashMap<String, Long>()
    }
}
