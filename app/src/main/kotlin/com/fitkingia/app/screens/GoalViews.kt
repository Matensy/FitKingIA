package com.fitkingia.app.screens

import android.view.ViewGroup
import android.widget.LinearLayout
import com.fitkingia.app.ui.*
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.BodyRegion
import com.fitkingia.core.model.Fmt
import com.fitkingia.core.program.AlignmentReport
import com.fitkingia.core.program.RegionCoverage

/**
 * "Seu objetivo × seu treino": para cada região priorizada, os números que dizem se o programa
 * realmente treina o que a pessoa pediu (dias na semana, séries focadas, fatia das séries e ordem).
 */
fun ViewGroup.goalSection(report: AlignmentReport, kb: KnowledgeBase, onChoosePriority: (() -> Unit)? = null) {
    h2("Seu objetivo × seu treino")
    val priorities = report.coverage.filter { it.priority }
    card(stroke = if (priorities.any { !it.ok }) C.warning else null) {
        if (priorities.isEmpty()) {
            report.lines.forEach { explanation(it) }
            onChoosePriority?.let { go -> button("🍑 Priorizar uma região (ex.: glúteos)", Btn.GHOST, bottom = 0) { go() } }
            return@card
        }
        for (c in priorities) {
            row(bottom = 6) {
                val t = text("${c.region.emoji}  ${c.region.label}", 16f, bold = true, bottom = 0)
                t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                badge(if (c.ok) "✅ no alvo" else "⚠️ atenção", if (c.ok) C.success else C.warning, bottom = 0)
            }
            metric("Treinos na semana", "${c.frequency} de ${report.sessions} (mínimo ${c.minFrequency})", c.frequency >= c.minFrequency,
                c.frequency.toDouble() / c.minFrequency.coerceAtLeast(1))
            val perMuscle = if (kb.trackedMuscles.count { it.focusRegion == c.region } > 1) "/músculo" else ""
            metric("Séries focadas por semana", "${c.focusedSets} (normal: ${Fmt.num(c.normalTarget)}$perMuscle)",
                c.focusedOk, c.focusedSets / (c.normalTarget * 1.5).coerceAtLeast(1.0))
            if (c.minShare > 0) metric("Fatia das séries de ${c.halfName()}", "${(c.shareOfHalf * 100).toInt()}% (mín. ${(c.minShare * 100).toInt()}%)",
                c.shareOfHalf + 1e-9 >= c.minShare, c.shareOfHalf)
            if (c.sessionsWithCompound > 0) kv("Abre o treino", "${c.leadingSessions} de ${c.sessionsWithCompound} dias",
                if (c.leadingSessions == c.sessionsWithCompound) C.success else C.warning)
            space(6)
        }
        // Os números já estão acima; ficam os avisos (com o porquê) e as notas gerais.
        (report.warnings + report.lines.filterNot { it.text.startsWith("✅") }).forEach { explanation(it, 13f) }
    }
}

private fun ViewGroup.metric(label: String, value: String, ok: Boolean, fraction: Double) {
    kv(label, value, if (ok) C.text else C.warning, bottom = 2)
    bar(fraction, if (ok) C.success else C.warning, height = 6, bottom = 8)
}

private fun RegionCoverage.halfName() = when (region) {
    BodyRegion.GLUTES, BodyRegion.LEGS -> "inferiores"
    BodyRegion.CORE -> "toda a semana"
    else -> "superiores"
}
