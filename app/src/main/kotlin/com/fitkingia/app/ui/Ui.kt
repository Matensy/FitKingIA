package com.fitkingia.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.explain.Provenance

/** Paleta escura do app (contraste alto para usar na academia). */
object C {
    const val bg = 0xFF111418.toInt()
    const val surface = 0xFF1B1F26.toInt()
    const val surface2 = 0xFF252B34.toInt()
    const val stroke = 0xFF313946.toInt()
    const val accent = 0xFFFF6B2C.toInt()
    const val accentDark = 0xFF3A2216.toInt()
    const val text = 0xFFF2F4F7.toInt()
    const val muted = 0xFF9AA4B2.toInt()
    const val success = 0xFF3DDC97.toInt()
    const val warning = 0xFFFFB020.toInt()
    const val danger = 0xFFFF5A5F.toInt()
    const val fact = 0xFF4DA3FF.toInt()
    const val rule = 0xFF3DDC97.toInt()
    const val ai = 0xFFB18CFF.toInt()
    const val onAccent = 0xFF1A0E07.toInt()
}

fun Context.dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
fun View.dp(v: Number): Int = context.dp(v)

fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        setCornerRadius(radius)
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

fun ripple(content: Drawable, radius: Float): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, rounded(Color.WHITE, radius))

private fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(width, height)

/** Adiciona [v] com margem inferior (o "ritmo" vertical das telas). */
fun <V : View> ViewGroup.add(v: V, bottom: Int = 8, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, top: Int = 0): V {
    val p = when (this) {
        // Em linhas horizontais, cada item ocupa só o necessário (quem quiser esticar usa peso).
        is LinearLayout -> if (orientation == LinearLayout.HORIZONTAL)
            lp(if (width == ViewGroup.LayoutParams.MATCH_PARENT) ViewGroup.LayoutParams.WRAP_CONTENT else width)
        else lp(width).apply { bottomMargin = dp(bottom); topMargin = dp(top) }
        is FrameLayout -> FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        else -> ViewGroup.MarginLayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom) }
    }
    addView(v, p)
    return v
}

fun ViewGroup.text(
    s: CharSequence, size: Float = 15f, color: Int = C.text, bold: Boolean = false, bottom: Int = 6,
    gravity: Int = Gravity.START, maxLines: Int = 0,
): TextView {
    val t = TextView(context)
    t.text = s
    t.setTextColor(color)
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    t.setLineSpacing(0f, 1.15f)
    t.gravity = gravity
    if (bold) t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    if (maxLines > 0) { t.maxLines = maxLines; t.ellipsize = TextUtils.TruncateAt.END }
    return add(t, bottom)
}

fun ViewGroup.h1(s: CharSequence) = text(s, 24f, bold = true, bottom = 4)
fun ViewGroup.h2(s: CharSequence, top: Int = 8) = text(s, 18f, bold = true, bottom = 8).also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(top) }
fun ViewGroup.h3(s: CharSequence) = text(s, 16f, bold = true, bottom = 4)
fun ViewGroup.body(s: CharSequence) = text(s, 15f)
fun ViewGroup.muted(s: CharSequence, size: Float = 13f) = text(s, size, C.muted)
fun ViewGroup.label(s: CharSequence) = text(s.toString().uppercase(), 12f, C.muted, bold = true, bottom = 6)

fun ViewGroup.space(h: Int) = add(View(context), bottom = 0).also { it.layoutParams.height = dp(h) }

fun ViewGroup.divider() = add(View(context).apply { setBackgroundColor(C.stroke) }, bottom = 10, top = 2).also { it.layoutParams.height = dp(1) }

fun ViewGroup.column(bottom: Int = 8, block: LinearLayout.() -> Unit = {}): LinearLayout =
    add(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; block() }, bottom)

fun ViewGroup.row(bottom: Int = 8, gravity: Int = Gravity.CENTER_VERTICAL, block: LinearLayout.() -> Unit = {}): LinearLayout =
    add(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setGravity(gravity); block() }, bottom)

fun ViewGroup.card(
    bottom: Int = 12, color: Int = C.surface, stroke: Int? = null, onClick: (() -> Unit)? = null, block: LinearLayout.() -> Unit,
): LinearLayout {
    val c = LinearLayout(context)
    c.orientation = LinearLayout.VERTICAL
    val r = dp(16).toFloat()
    val bg = rounded(color, r, stroke, dp(1))
    c.background = if (onClick != null) ripple(bg, r) else bg
    c.setPadding(dp(16), dp(14), dp(16), dp(10))
    if (onClick != null) { c.isClickable = true; c.setOnClickListener { onClick() } }
    c.block()
    return add(c, bottom)
}

enum class Btn { PRIMARY, SECONDARY, GHOST, DANGER }

fun ViewGroup.button(label: String, style: Btn = Btn.PRIMARY, bottom: Int = 8, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, enabled: Boolean = true, onClick: () -> Unit): TextView {
    val b = TextView(context)
    b.text = label
    b.gravity = Gravity.CENTER
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    b.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    b.setPadding(dp(16), dp(13), dp(16), dp(13))
    val r = dp(14).toFloat()
    val (bgColor, fg, stroke) = when (style) {
        Btn.PRIMARY -> Triple(C.accent, C.onAccent, null)
        Btn.SECONDARY -> Triple(C.surface2, C.text, C.stroke)
        Btn.GHOST -> Triple(Color.TRANSPARENT, C.accent, C.stroke)
        Btn.DANGER -> Triple(0xFF3A1A1C.toInt(), C.danger, C.danger)
    }
    b.setTextColor(if (enabled) fg else C.muted)
    b.background = ripple(rounded(if (enabled) bgColor else C.surface2, r, stroke, dp(1)), r)
    b.isClickable = true
    b.setOnClickListener { if (enabled) onClick() }
    b.contentDescription = label
    return add(b, bottom, width)
}

/** Botões lado a lado com o mesmo peso. */
fun ViewGroup.buttonRow(vararg items: Triple<String, Btn, () -> Unit>, bottom: Int = 8): LinearLayout = row(bottom) {
    items.forEachIndexed { i, (label, style, action) ->
        val b = button(label, style, bottom = 0, onClick = action)
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = dp(8) }
        b.setPadding(dp(8), dp(12), dp(8), dp(12))
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
}

fun chip(ctx: Context, label: String, selected: Boolean, small: Boolean = false, onClick: () -> Unit): TextView {
    val t = TextView(ctx)
    t.text = label
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 13f else 14f)
    t.setTextColor(if (selected) C.onAccent else C.text)
    if (selected) t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    val h = ctx.dp(if (small) 10 else 14)
    val v = ctx.dp(if (small) 7 else 9)
    t.setPadding(h, v, h, v)
    val r = ctx.dp(20).toFloat()
    t.background = ripple(rounded(if (selected) C.accent else C.surface2, r, if (selected) null else C.stroke, ctx.dp(1)), r)
    t.isClickable = true
    t.setOnClickListener { onClick() }
    t.contentDescription = label + if (selected) " (selecionado)" else ""
    return t
}

/** Alternativas como chips. Seleção única ou múltipla é decidida por quem chama em [onTap]. */
fun <T> ViewGroup.chips(options: List<Pair<T, String>>, selected: (T) -> Boolean, small: Boolean = false, bottom: Int = 10, onTap: (T) -> Unit): FlowLayout {
    val f = FlowLayout(context)
    options.forEach { (v, l) -> f.addView(chip(context, l, selected(v), small) { onTap(v) }) }
    return add(f, bottom)
}

/** Opção grande (cartão) para perguntas de alternativa única. */
fun ViewGroup.option(title: String, subtitle: String? = null, selected: Boolean, bottom: Int = 8, onClick: () -> Unit): LinearLayout =
    card(bottom = bottom, color = if (selected) C.accentDark else C.surface, stroke = if (selected) C.accent else C.stroke, onClick = onClick) {
        row(bottom = 0) {
            val t = column(bottom = 0) {
                text(title, 16f, if (selected) C.text else C.text, bold = selected, bottom = if (subtitle != null) 2 else 4)
                subtitle?.let { muted(it) }
            }
            t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val mark = TextView(context).apply {
                text = if (selected) "●" else "○"
                setTextColor(if (selected) C.accent else C.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            addView(mark)
        }
    }

/** Valor numérico ajustado só com toques (− / +). */
fun ViewGroup.stepper(
    value: String, unit: String = "", big: Boolean = false, bottom: Int = 10,
    steps: List<Pair<String, () -> Unit>>,
): LinearLayout = row(bottom, Gravity.CENTER) {
    val half = steps.size / 2
    fun stepButton(label: String, action: () -> Unit) {
        val b = TextView(context)
        b.text = label
        b.gravity = Gravity.CENTER
        b.setTextColor(C.text)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 18f else 15f)
        b.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val r = dp(24).toFloat()
        b.background = ripple(rounded(C.surface2, r, C.stroke, dp(1)), r)
        b.isClickable = true
        b.setOnClickListener { action() }
        b.contentDescription = label
        val size = dp(if (big) 52 else 44)
        addView(b, LinearLayout.LayoutParams(if (label.length > 2) ViewGroup.LayoutParams.WRAP_CONTENT else size, size).apply { marginStart = dp(4); marginEnd = dp(4) })
        if (label.length > 2) b.setPadding(dp(10), 0, dp(10), 0)
    }
    steps.take(half).forEach { stepButton(it.first, it.second) }
    val v = TextView(context)
    v.text = if (unit.isEmpty()) value else "$value $unit"
    v.gravity = Gravity.CENTER
    v.setTextColor(C.text)
    v.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 34f else 20f)
    v.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    steps.drop(half).forEach { stepButton(it.first, it.second) }
}

/** Barra de progresso simples (0..1). */
fun ViewGroup.bar(fraction: Double, color: Int = C.accent, height: Int = 8, bottom: Int = 8): FrameLayout {
    val f = FrameLayout(context)
    f.background = rounded(C.surface2, dp(height).toFloat())
    val fill = View(context)
    fill.background = rounded(color, dp(height).toFloat())
    f.addView(fill, FrameLayout.LayoutParams(0, dp(height)))
    add(f, bottom).layoutParams.height = dp(height)
    f.post {
        val w = (f.width * fraction.coerceIn(0.0, 1.0)).toInt()
        fill.layoutParams = FrameLayout.LayoutParams(if (fraction > 0 && w < dp(height)) dp(height) else w, dp(height))
    }
    return f
}

fun ViewGroup.badge(s: String, color: Int, bottom: Int = 6): TextView {
    val t = TextView(context)
    t.text = s
    t.setTextColor(color)
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    t.setPadding(dp(8), dp(3), dp(8), dp(3))
    t.background = rounded(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), dp(10).toFloat())
    return add(t, bottom, ViewGroup.LayoutParams.WRAP_CONTENT)
}

fun provenanceColor(p: Provenance) = when (p) {
    Provenance.FACT -> C.fact
    Provenance.SYSTEM_RULE -> C.rule
    Provenance.AI_SUGGESTION -> C.ai
}

/** Explicação com a marca de proveniência (🔵 fato, 🟢 regra, 🟣 assistente). */
fun ViewGroup.explanation(e: Explanation, size: Float = 14f) = row(bottom = 8, gravity = Gravity.TOP) {
    val dot = View(context)
    dot.background = rounded(provenanceColor(e.provenance), dp(4).toFloat())
    addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { topMargin = dp(7); marginEnd = dp(10) })
    val t = text(e.text, size, C.text, bottom = 0)
    t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}

/** Linha "rótulo .......... valor". */
fun ViewGroup.kv(k: String, v: String, valueColor: Int = C.text, bottom: Int = 6) = row(bottom, Gravity.TOP) {
    val a = text(k, 14f, C.muted, bottom = 0)
    a.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }
    val b = text(v, 14f, valueColor, bold = true, bottom = 0, gravity = Gravity.END)
    b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}

/** Lista com marcadores. */
fun ViewGroup.bullets(items: List<String>, size: Float = 14f, color: Int = C.text) = items.forEach { text("•  $it", size, color, bottom = 4) }
