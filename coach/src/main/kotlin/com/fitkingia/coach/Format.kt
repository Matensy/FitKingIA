package com.fitkingia.coach

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.Claim
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.Stance
import com.fitkingia.core.model.Fmt
import com.fitkingia.core.program.PlannedExercise
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.pt

/** Texto das respostas. 🟣 = fala do assistente; 🟢 = regra do motor; 🔵 = fato com fonte. */
internal object Say {
    fun me(text: String) = "🟣 $text"
    fun rule(text: String) = "🟢 $text"
    fun fact(text: String) = "🔵 $text"
    fun of(e: Explanation) = e.toString()

    fun rest(s: Int) = if (s % 60 == 0) "${s / 60} min" else if (s > 60) "${s / 60}min${s % 60}s" else "${s}s"

    fun exercise(i: Int, e: PlannedExercise): String {
        val p = e.prescription
        return "  ${i + 1}. ${e.exercise.name} — ${e.sets} × ${p.target}, RIR ${p.rir}, descanso ${rest(e.restSeconds)}"
    }

    fun session(s: PlannedSession): String = buildString {
        append("${s.name} (${s.day?.pt() ?: "sem dia"}, ~${s.estimatedMinutes} min)")
        s.exercises.forEachIndexed { i, e -> append('\n').append(exercise(i, e)) }
    }

    fun claim(kb: KnowledgeBase, c: Claim, maxSources: Int = 3): String = buildString {
        append(fact(c.statement))
        append("\n   Nível: ${c.evidenceLevel.label}")
        if (c.isConflicting) append(" — ⚠️ evidência conflitante: há estudos com resultados diferentes")
        for (cs in c.sources.filter { it.stance != Stance.CONTEXT }.take(maxSources)) {
            val s = kb.source(cs.sourceId)
            val mark = if (cs.stance == Stance.SUPPORTS) "✔" else "✖"
            append("\n   $mark ${s.authors.substringBefore(",")} (${s.year ?: "s.d."}) — ${s.title.take(90)}${if (s.title.length > 90) "…" else ""}")
            s.doi?.let { append(" · doi:$it") }
        }
    }

    fun kg(v: Double) = Fmt.kg(v)
    fun num(v: Double) = Fmt.num(v)
}
