package com.fitkingia.cli

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.explain.WhyReport
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.Program
import com.fitkingia.core.program.ProgramGenerator.Companion.fmt
import com.fitkingia.core.program.ptCapitalized
import com.fitkingia.core.analytics.VolumeDashboard

/** Formatação de terminal. Toda saída marca a proveniência (🔵 fato, 🟢 regra, 🟣 IA). */
class Printer(private val kb: KnowledgeBase, private val out: (String) -> Unit = ::println) {
    fun line(s: String = "") = out(s)
    fun rule(title: String) { out(""); out("━".repeat(60)); out(" $title"); out("━".repeat(60)) }

    fun explanations(items: List<Explanation>) = items.forEach { out(it.toString()) }

    fun session(s: PlannedSession) {
        out("")
        val day = s.day?.ptCapitalized()?.let { "$it — " } ?: ""
        out("🏋️ $day${s.name}  (~${s.estimatedMinutes} min${s.budgetMinutes?.let { " de $it" } ?: ""})")
        for ((i, e) in s.exercises.withIndex()) {
            val p = e.prescription
            out("  ${i + 1}. ${e.exercise.name.padEnd(46)} ${e.sets} × ${p.target.padEnd(7)} RIR ${p.rir}  " +
                "descanso ${restTxt(e.restSeconds)}  [${e.role.label.lowercase()}]")
        }
    }

    fun program(p: Program) {
        rule("PROGRAMA — ${p.split.name} (${p.focus.label}, ${p.tier.label})")
        p.sessions.forEach(::session)
        out("")
        out("⏱ Tempo semanal estimado: ${p.weeklyMinutes} min")
        volume(p)
        rule("POR QUE ESTE PROGRAMA")
        explanations(p.explanations)
        if (p.warnings.isNotEmpty()) { rule("AVISOS"); explanations(p.warnings) }
    }

    fun volume(p: Program) {
        out("")
        out("📊 Volume semanal planejado (séries, contagem fracionada)")
        for (m in kb.trackedMuscles) {
            val v = p.weeklyVolume[m.id] ?: 0.0
            val t = p.volumeTargets.getValue(m.id)
            val status = when { v + 1e-9 < t.min -> "abaixo"; v > t.max + 1e-9 -> "acima"; else -> "ok" }
            out("  ${m.name.padEnd(42)} ${VolumeDashboard.bar(v, t.target)} ${fmt(v).padStart(5)}  (faixa ${fmt(t.min)}–${fmt(t.max)}) $status")
        }
    }

    fun why(r: WhyReport) {
        rule(r.title)
        r.lines.forEach { out("  • $it") }
        for (rule in r.rules) {
            out("")
            out("🟢 Regra ${rule.id} — ${rule.basis.label}: ${rule.description}")
            out("   Justificativa: ${rule.rationale}")
            for (e in rule.evidence) {
                out("   🔵 ${e.statement}")
                out("      Nível: ${e.level.label}${if (e.conflicting) " — ⚠️ evidência conflitante" else ""}")
                e.supports.forEach { out("      ✔ ${it.citation}${it.url?.let { u -> " <$u>" } ?: ""} (verificado: ${it.lastVerified ?: "pendente"})") }
                e.contradicts.forEach { out("      ✖ ${it.citation}${it.url?.let { u -> " <$u>" } ?: ""}") }
                e.context.forEach { out("      ℹ ${it.citation}") }
            }
        }
    }

    private fun restTxt(s: Int) = if (s % 60 == 0) "${s / 60} min" else if (s > 60) "${s / 60}min${s % 60}s" else "${s}s"
}
