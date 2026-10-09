package com.fitkingia.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Como o conteúdo mudou num render() do MainActivity (decide a animação). */
enum class ScreenChange {
    /** refresh(): mesma tela; nada de animar a tela inteira (só o que mudou: toque, barra, número). */
    NONE,
    /** Tela nova empilhada: entra da direita. */
    PUSH,
    /** Voltar: a tela anterior entra da esquerda. */
    POP,
    /** Troca de aba ou nova raiz: fade cruzado curto. */
    ROOT,
    /** Próxima página da mesma tela (questionário, próximo exercício). */
    FORWARD,
    /** Página anterior da mesma tela. */
    BACK,
    /** Mesma página recarregada do topo. */
    FADE,
}

/**
 * Movimento do app: curto (150–350 ms), sutil e nunca no caminho de quem está treinando.
 *
 * - [enabled] é o interruptor global (os testes desligam para resultados determinísticos);
 * - a preferência do sistema "remover animações" (escala de animação 0) também desliga tudo;
 * - só a navegação anima a tela inteira; um refresh() comum só dá retorno no que foi tocado.
 */
object Motion {
    /** Interruptor global. Os testes de UI definem false no setUp. */
    @JvmStatic @Volatile var enabled = true

    const val PRESS_SCALE = 0.96f
    private const val CASCADE_STEP = 34L
    private const val CASCADE_SLOTS = 10
    private const val CASCADE_MAX_VIEWS = 30

    internal val easeOut: Interpolator = DecelerateInterpolator(2f)
    internal val easeIn: Interpolator = AccelerateInterpolator(1.5f)
    internal val easeInOut: Interpolator = AccelerateDecelerateInterpolator()
    internal val linear: Interpolator = LinearInterpolator()
    internal val spring: Interpolator = OvershootInterpolator(3f)
    internal val bounce: Interpolator = OvershootInterpolator(4f)

    // ValueAnimator.areAnimatorsEnabled() só existe a partir da API 26 e o app compila contra o
    // android.jar da API 23, por isso a chamada é por reflexão (com a configuração global como reserva).
    private val areAnimatorsEnabled: java.lang.reflect.Method? by lazy {
        try { ValueAnimator::class.java.getMethod("areAnimatorsEnabled") } catch (e: Exception) { null }
    }

    /** O sistema permite animações? ("Remover animações" / escala de duração 0 = não.) */
    fun systemAllows(ctx: Context): Boolean {
        val m = areAnimatorsEnabled
        if (m != null) {
            try { return m.invoke(null) as Boolean } catch (e: Exception) { /* cai na configuração global */ }
        }
        return try {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        } catch (e: Exception) { true }
    }

    fun on(ctx: Context): Boolean = enabled && systemAllows(ctx)

    // -----------------------------------------------------------------------------------------
    // Contexto do render: o kit (barra, stepper, contador…) sabe se está numa navegação
    // (anima a entrada), num refresh (anima só o que mudou) ou fora de uma tela (diálogos).
    // -----------------------------------------------------------------------------------------

    /** Build de uma navegação (push/aba): componentes podem animar a entrada. */
    var entering = false
        private set
    /** Build de um refresh/troca de página: componentes animam só valores que mudaram. */
    var changing = false
        private set
    private var rendering = false
    /**
     * Tela do último render. Referência fraca: este objeto vive o processo inteiro e a tela
     * guarda a Activity (Views, foto da transição); uma referência forte seguraria a Activity
     * destruída até o próximo render.
     */
    private var screenKey: WeakReference<Any>? = null
    private val memory = HashMap<String, Any>()
    private val touched = HashSet<String>()
    private val counters = HashMap<String, Int>()
    private var confetti: WeakReference<View>? = null

    fun beginRender(ctx: Context, screen: Any, change: ScreenChange) {
        val on = on(ctx)
        val navigation = change == ScreenChange.PUSH || change == ScreenChange.ROOT || change == ScreenChange.POP
        rendering = on
        entering = on && (change == ScreenChange.PUSH || change == ScreenChange.ROOT)
        changing = on && !navigation
        if (navigation || screen !== screenKey?.get()) memory.clear()
        if (navigation) clearConfetti()
        if (screen !== screenKey?.get()) screenKey = WeakReference(screen)
        counters.clear()
    }

    fun endRender() {
        // A memória fica só com o que existe neste render: componente que sumiu (ex.: o anel do
        // descanso que acabou) conta como novo quando voltar.
        if (rendering) memory.keys.retainAll(touched)
        touched.clear()
        rendering = false
        entering = false
        changing = false
    }

    /**
     * Valor que o mesmo componente (o n-ésimo do mesmo [kind] na tela) tinha no render anterior
     * desta tela, e guarda o atual. Fora de um render (diálogos) devolve null.
     */
    internal fun previous(kind: String, value: Any): Any? {
        if (!rendering) return null
        val n = counters[kind] ?: 0
        counters[kind] = n + 1
        val key = "$kind#$n"
        val old = memory[key]
        memory[key] = value
        touched.add(key)
        return old
    }

    // -----------------------------------------------------------------------------------------
    // Primitivas
    // -----------------------------------------------------------------------------------------

    internal fun scale(v: View, from: Float, to: Float, ms: Long, interp: Interpolator, delay: Long = 0): Animator {
        v.scaleX = from
        v.scaleY = from
        return ObjectAnimator.ofPropertyValuesHolder(v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to),
        ).apply { duration = ms; startDelay = delay; interpolator = interp; start() }
    }

    /** Volta de um toque: a view (reconstruída pelo refresh) "quica" de levemente menor até o normal. */
    fun spring(v: View, from: Float = PRESS_SCALE) {
        if (!on(v.context)) return
        scale(v, from, 1f, 280, spring)
    }

    /** "Pop" de seleção: escala pequena → normal com sobressalto. */
    fun pop(v: View, from: Float = 0.86f, delay: Long = 0) {
        if (!on(v.context)) return
        scale(v, from, 1f, 320, bounce, delay)
    }

    /** Entrada de destaque (troféu, bandeira): só na navegação; aparece do zero com sobressalto. */
    fun popIn(v: View, delay: Long = 120) {
        if (!entering) return
        scale(v, 0f, 1f, 420, OvershootInterpolator(2.6f), delay)
    }

    /** Contador que sobe de 0 até [target] (só na navegação; num refresh mostra o valor direto). */
    fun countUp(tv: TextView, target: Int, delay: Long = 320, format: (Int) -> String) {
        tv.text = format(target)
        if (!entering || target <= 0) return
        tv.text = format(0)
        ValueAnimator.ofInt(0, target).apply {
            duration = 900
            startDelay = delay
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { tv.text = format(it.animatedValue as Int) }
            start()
        }
    }

    /** Confete por cima da tela inteira (~1,5 s). Só na navegação; some na próxima navegação. */
    fun celebrate(anchor: View) {
        if (!entering) return
        val host = anchor.rootView.findViewById(android.R.id.content) as? ViewGroup ?: return
        clearConfetti()
        val c = ConfettiView(anchor.context)
        host.addView(c, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        confetti = WeakReference(c)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ConfettiView.DURATION_MS
            startDelay = 120
            interpolator = linear
            addUpdateListener { c.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { (c.parent as? ViewGroup)?.removeView(c) }
            })
            start()
        }
    }

    private fun clearConfetti() {
        confetti?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        confetti = null
    }

    /** Barra de progresso: cresce do zero na navegação; num refresh anima do valor anterior. */
    internal fun bar(fill: BarFill, fraction: Float) {
        val old = previous("bar", fraction) as? Float
        val from = when {
            entering -> 0f
            changing && old != null -> old
            else -> fraction
        }
        if (from == fraction) { fill.shown = fraction; return }
        fill.shown = from
        ValueAnimator.ofFloat(from, fraction).apply {
            duration = if (entering) 350 else 260
            startDelay = if (entering) 140 else 0
            interpolator = easeOut
            addUpdateListener { fill.shown = it.animatedValue as Float }
            start()
        }
    }

    /** Número do stepper que mudou num refresh: rola para cima (aumentou) ou para baixo (diminuiu). */
    internal fun valueTick(v: TextView) {
        val text = v.text.toString()
        val old = previous("value", text) as? String
        if (!changing || old == null || old == text) return
        val a = firstNumber(old)
        val b = firstNumber(text)
        val dir = if (a != null && b != null) (if (b > a) 1 else if (b < a) -1 else 0) else 0
        val dy = v.dp(10).toFloat() * dir
        v.translationY = dy
        v.alpha = 0.35f
        ObjectAnimator.ofPropertyValuesHolder(v,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dy, 0f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.35f, 1f),
        ).apply { duration = 180; interpolator = easeOut; start() }
    }

    private val number = Regex("-?\\d+(?:[.,]\\d+)?")
    private fun firstNumber(s: String): Double? = number.find(s)?.value?.replace(',', '.')?.toDoubleOrNull()

    /** Anel do descanso: enche do zero quando aparece (descanso novo), não a cada refresh. */
    internal fun ringAppears(left: Int): Boolean {
        val old = previous("ring", left)
        return (changing || entering) && old == null
    }

    // -----------------------------------------------------------------------------------------
    // Entrada escalonada
    // -----------------------------------------------------------------------------------------

    /**
     * Os blocos (filhos diretos) entram com fade + leve subida em cascata. Os 10 primeiros têm
     * atraso crescente; os seguintes entram junto com o 10º (e depois de 30 já estão fora da tela).
     */
    internal fun cascade(container: ViewGroup, rise: Float, baseDelay: Long = 40) {
        var n = 0
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (n >= CASCADE_MAX_VIEWS) break
            val slot = minOf(n, CASCADE_SLOTS - 1)
            val alpha = c.alpha
            val y = c.translationY
            c.alpha = 0f
            c.translationY = y + rise
            ObjectAnimator.ofPropertyValuesHolder(c,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, alpha),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, y + rise, y),
            ).apply { duration = 240; startDelay = baseDelay + slot * CASCADE_STEP; interpolator = easeOut; start() }
            n++
        }
    }

    // -----------------------------------------------------------------------------------------
    // Retorno de toque
    // -----------------------------------------------------------------------------------------

    private var tapped: WeakReference<View>? = null
    private var tappedAt = 0L

    /** Chamado pelo kit ao tocar num botão/chip/cartão (antes da ação). */
    fun noteTap(v: View) {
        tapped = WeakReference(v)
        tappedAt = SystemClock.uptimeMillis()
    }

    class Tap internal constructor(val root: Int, val path: IntArray, val wasSelected: Boolean, val cls: Class<*>)

    /**
     * Antes de um refresh reconstruir a tela: onde estava (caminho de índices) o último elemento
     * tocado. Depois do refresh, [replayTap] dá a volta da "mola" no elemento equivalente.
     */
    fun captureTap(vararg roots: ViewGroup): Tap? {
        val v = tapped?.get() ?: return null
        tapped = null
        if (!enabled || SystemClock.uptimeMillis() - tappedAt > 1000) return null
        roots.forEachIndexed { i, root -> pathTo(root, v)?.let { return Tap(i, it, v.isSelected, v.javaClass) } }
        return null
    }

    private fun pathTo(root: ViewGroup, v: View): IntArray? {
        var depth = 0
        var cur: View = v
        while (cur !== root) { cur = cur.parent as? View ?: return null; depth++ }
        val path = IntArray(depth)
        cur = v
        for (i in depth - 1 downTo 0) {
            val parent = cur.parent as ViewGroup
            path[i] = parent.indexOfChild(cur)
            cur = parent
        }
        return path
    }

    fun replayTap(tap: Tap?, vararg roots: ViewGroup) {
        if (tap == null) return
        var v: View = roots.getOrNull(tap.root) ?: return
        for (i in tap.path) v = (v as? ViewGroup)?.takeIf { i < it.childCount }?.getChildAt(i) ?: return
        if (v.javaClass != tap.cls || !v.isClickable) return
        if (v.isSelected && !tap.wasSelected) pop(v) else spring(v)
    }

    // -----------------------------------------------------------------------------------------
    // Abertura
    // -----------------------------------------------------------------------------------------

    /** Coroa cresce com fade e depois "respira"; textos entram em seguida. Devolve para cancelar. */
    fun splash(crown: View, vararg rest: View): List<Animator> {
        if (!on(crown.context)) return emptyList()
        val list = ArrayList<Animator>()
        crown.alpha = 0f
        list += scale(crown, 0.55f, 1f, 520, OvershootInterpolator(1.8f))
        list += ObjectAnimator.ofFloat(crown, View.ALPHA, 0f, 1f).apply { duration = 300; start() }
        list += ObjectAnimator.ofPropertyValuesHolder(crown,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
        ).apply {
            duration = 850; startDelay = 700; interpolator = easeInOut
            repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            start()
        }
        rest.forEachIndexed { i, v ->
            val rise = v.dp(10).toFloat()
            v.alpha = 0f
            v.translationY = rise
            list += ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, rise, 0f),
            ).apply { duration = 340; startDelay = 180 + i * 120L; interpolator = easeOut; start() }
        }
        return list
    }
}

/**
 * Transições entre telas. Antes de reconstruir, fotografa a área de conteúdo (overlay por cima,
 * que sai com fade e deslize); depois, o conteúdo novo entra na direção da navegação.
 */
class ScreenTransitions(private val host: ViewGroup, private val area: View) {
    private var bitmap: Bitmap? = null
    private var shot: BitmapDrawable? = null
    private var exit: ValueAnimator? = null
    private var fresh = false
    private val enter = ArrayList<Animator>()

    /** Chamado antes de limpar o conteúdo. */
    fun capture() {
        fresh = false
        val w = area.width
        val h = area.height
        // Dois renders no mesmo quadro (ex.: setRoot + push): o conteúdo ainda não foi desenhado;
        // fica a foto anterior (se houver) saindo normalmente.
        if (w <= 0 || h <= 0 || area.isLayoutRequested) return
        exit?.end()
        try {
            val bmp = bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { bitmap = it }
            val canvas = Canvas(bmp)
            canvas.drawColor(C.bg)
            // View.draw() não aplica a rolagem da própria View (quem aplica é o pai): sem isto a
            // foto de uma tela rolada mostraria o topo dela.
            canvas.translate(-area.scrollX.toFloat(), -area.scrollY.toFloat())
            area.draw(canvas)
            val d = BitmapDrawable(area.resources, bmp)
            d.setBounds(area.left, area.top, area.right, area.bottom)
            host.overlay.add(d)
            shot = d
            fresh = true
        } catch (e: Throwable) {
            // Sem memória para a foto: a troca acontece sem a saída animada.
            bitmap = null
        }
    }

    /** Foto da última captura (testes). */
    internal val lastCapture: Bitmap? get() = bitmap

    /** Solta a foto (tela cheia) e o que estiver animando: Activity destruída ou app fora da tela. */
    fun release() {
        exit?.cancel()
        exit = null
        // end(), não cancel(): cancelada no meio, a entrada deixaria o conteúdo transparente ou
        // deslocado (ex.: app minimizado logo depois de um toque que trocou de tela).
        for (a in enter) a.end()
        enter.clear()
        shot?.let { host.overlay.remove(it) }
        shot = null
        fresh = false
        bitmap = null
    }

    /** Chamado depois de montar a tela nova. */
    fun play(change: ScreenChange, content: ViewGroup, title: View, footer: View) {
        for (a in enter) a.cancel()
        enter.clear()
        for (v in listOf<View>(content, title, footer)) { v.alpha = 1f; v.translationX = 0f }
        val d = content.dp(28).toFloat()
        when (change) {
            ScreenChange.PUSH -> {
                slide(content, d, fade = false, ms = 280)
                slide(title, d / 2, fade = true, ms = 220)
                fade(footer, 220, 80)
                Motion.cascade(content, content.dp(10).toFloat())
            }
            ScreenChange.POP -> {
                slide(content, -d, fade = true, ms = 260)
                slide(title, -d / 2, fade = true, ms = 220)
                fade(footer, 200, 40)
            }
            ScreenChange.ROOT -> {
                fade(title, 180, 0)
                fade(footer, 200, 60)
                Motion.cascade(content, content.dp(8).toFloat())
            }
            ScreenChange.FORWARD -> slide(content, d, fade = true, ms = 240)
            ScreenChange.BACK -> slide(content, -d, fade = true, ms = 240)
            ScreenChange.FADE -> fade(content, 180, 0)
            ScreenChange.NONE -> {}
        }
        if (fresh) playExit(change, d)
        fresh = false
    }

    private fun slide(v: View, from: Float, fade: Boolean, ms: Long) {
        v.translationX = from
        val holders = ArrayList<PropertyValuesHolder>()
        holders += PropertyValuesHolder.ofFloat(View.TRANSLATION_X, from, 0f)
        if (fade) { v.alpha = 0f; holders += PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f) }
        enter += ObjectAnimator.ofPropertyValuesHolder(v, *holders.toTypedArray()).apply {
            duration = ms; interpolator = Motion.easeOut; start()
        }
    }

    private fun fade(v: View, ms: Long, delay: Long) {
        v.alpha = 0f
        enter += ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).apply { duration = ms; startDelay = delay; interpolator = Motion.easeOut; start() }
    }

    private fun playExit(change: ScreenChange, d: Float) {
        val dr = shot ?: return
        val dx = when (change) {
            ScreenChange.PUSH, ScreenChange.FORWARD -> -d
            ScreenChange.POP, ScreenChange.BACK -> d
            else -> 0f
        }
        val base = Rect(dr.bounds)
        exit = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            interpolator = Motion.easeIn
            addUpdateListener {
                val f = it.animatedValue as Float
                val off = (dx * f).toInt()
                dr.alpha = ((1f - f) * 255).toInt().coerceIn(0, 255)
                dr.setBounds(base.left + off, base.top, base.right + off, base.bottom)
                host.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    host.overlay.remove(dr)
                    if (shot === dr) shot = null
                    if (exit === animation) exit = null
                    host.invalidate()
                }
            })
            start()
        }
    }
}

/** Retorno de pressão: encolhe enquanto pressionado e volta com uma mola leve. */
internal class PressFeedback(private val v: View) {
    private var down = false
    private var anim: Animator? = null

    fun onStateChanged() {
        val pressed = v.isPressed
        if (pressed == down) return
        down = pressed
        if (!Motion.on(v.context)) return
        anim?.cancel()
        anim = if (pressed) Motion.scale(v, v.scaleX, Motion.PRESS_SCALE, 100, Motion.easeOut)
        else Motion.scale(v, v.scaleX, 1f, 280, Motion.spring)
    }
}

/** TextView do kit (botões, chips, stepper) com retorno de pressão. */
class PressableTextView(context: Context) : TextView(context) {
    // Nullable: o construtor da View pode chamar drawableStateChanged() antes deste campo existir.
    private var press: PressFeedback? = null
    init { press = PressFeedback(this) }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.onStateChanged()
    }
}

/** Cartão clicável do kit com retorno de pressão. */
class PressableLayout(context: Context) : LinearLayout(context) {
    private var press: PressFeedback? = null
    init { press = PressFeedback(this) }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.onStateChanged()
    }
}
