package com.fitkingia.core.progression

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.ExerciseId
import java.time.LocalDate
import kotlin.math.roundToInt

enum class PrType(val label: String) {
    HEAVIEST_LOAD("Maior carga"),
    MOST_REPS_AT_LOAD("Mais repetições com esta carga"),
    BEST_E1RM("Melhor 1RM estimado"),
    BEST_SESSION_VOLUME("Maior volume na sessão"),
}

data class PersonalRecord(val exerciseId: ExerciseId, val type: PrType, val date: LocalDate, val description: String)

object PersonalRecords {
    /** Compara a sessão nova com todo o histórico anterior. A primeira sessão vira linha de base (sem PR). */
    fun detect(kb: KnowledgeBase, new: ExerciseLog, previous: List<ExerciseLog>): List<PersonalRecord> {
        val prev = previous.filter { it.exerciseId == new.exerciseId && it.date <= new.date && it !== new }
        if (prev.isEmpty() || new.sets.isEmpty()) return emptyList()
        val name = kb.exercise(new.exerciseId).name
        val out = mutableListOf<PersonalRecord>()
        fun pr(t: PrType, what: String) = out.add(PersonalRecord(new.exerciseId, t, new.date, "🏆 NOVO PR — $name: $what (${t.label.lowercase()})"))

        val prevSets = prev.flatMap { it.sets }
        val heaviest = new.sets.maxBy { it.loadKg }
        if (heaviest.loadKg > (prevSets.maxOfOrNull { it.loadKg } ?: 0.0)) pr(PrType.HEAVIEST_LOAD, "${kg(heaviest.loadKg)} × ${heaviest.reps}")

        new.sets.filter { it.loadKg > 0 }.sortedByDescending { it.loadKg }.firstOrNull { s ->
            val best = prevSets.filter { it.loadKg >= s.loadKg }.maxOfOrNull { it.reps }
            best != null && s.reps > best && s.loadKg <= (prevSets.maxOfOrNull { it.loadKg } ?: 0.0)
        }?.let { pr(PrType.MOST_REPS_AT_LOAD, "${kg(it.loadKg)} × ${it.reps}") }

        val e1 = OneRepMax.bestOf(new)
        val prevE1 = prev.mapNotNull { OneRepMax.bestOf(it) }.maxOrNull()
        if (e1 != null && prevE1 != null && e1 > prevE1 + 1e-9) pr(PrType.BEST_E1RM, "~${kg(e1)} estimado")

        if (new.volumeKg > (prev.maxOfOrNull { it.volumeKg } ?: 0.0) && new.volumeKg > 0) pr(PrType.BEST_SESSION_VOLUME, "${new.volumeKg.roundToInt()} kg totais")
        return out
    }

    private fun kg(v: Double) = ProgressionEngine.fmtKg(v)
}

enum class TrendDirection(val label: String) {
    IMPROVING("Progredindo"), STABLE("Estável"), DECLINING("Em queda"), INSUFFICIENT_DATA("Dados insuficientes")
}

data class PerformanceTrend(
    val exerciseId: ExerciseId,
    val direction: TrendDirection,
    val e1rmBySession: List<Pair<LocalDate, Double>>,
    /** Variação média por sessão, em % do e1RM médio. */
    val pctPerSession: Double,
    /** Quantas sessões seguidas terminaram com e1RM menor que a anterior. */
    val decliningStreak: Int,
)

object Trends {
    /** Reconhece progresso mesmo quando carga e reps alternam (60×10 → 60×12 → 62,5×9 → 62,5×11). */
    fun of(exerciseId: ExerciseId, history: List<ExerciseLog>, window: Int = 6, thresholdPct: Double = 0.5): PerformanceTrend {
        val pts = history.filter { it.exerciseId == exerciseId }.sortedBy { it.date }
            .mapNotNull { l -> OneRepMax.bestOf(l)?.let { l.date to it } }.takeLast(window)
        if (pts.size < 3) return PerformanceTrend(exerciseId, TrendDirection.INSUFFICIENT_DATA, pts, 0.0, 0)
        val n = pts.size
        val xs = (0 until n).map { it.toDouble() }
        val ys = pts.map { it.second }
        val mx = xs.average(); val my = ys.average()
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.sumOf { (it - mx) * (it - mx) }
        val pct = slope / my * 100
        var streak = 0
        for (i in n - 1 downTo 1) if (ys[i] < ys[i - 1]) streak++ else break
        val dir = when {
            pct > thresholdPct -> TrendDirection.IMPROVING
            pct < -thresholdPct -> TrendDirection.DECLINING
            else -> TrendDirection.STABLE
        }
        return PerformanceTrend(exerciseId, dir, pts, pct, streak)
    }
}

data class DeloadAdvice(val recommended: Boolean, val declining: List<ExerciseId>, val explanation: Explanation)

/** Auto Deload: sugere revisão do ciclo quando há queda persistente em vários exercícios principais. */
class DeloadAdvisor(private val kb: KnowledgeBase) {
    private val rule = kb.ruleSet.deload

    fun advise(trends: List<PerformanceTrend>, recentReadiness: List<Int> = emptyList()): DeloadAdvice {
        val r = rule.params
        val valid = trends.filter { it.direction != TrendDirection.INSUFFICIENT_DATA }
        val declining = valid.filter { it.decliningStreak >= r.decliningSessions }.map { it.exerciseId }
        val share = if (valid.isEmpty()) 0.0 else declining.size.toDouble() / valid.size
        val lowReadiness = recentReadiness.isNotEmpty() && recentReadiness.average() < r.lowReadinessThreshold
        val recommended = share >= r.minShareOfMainLifts && (recentReadiness.isEmpty() || lowReadiness)
        val text = if (recommended)
            "Queda de desempenho em ${declining.size} de ${valid.size} exercícios principais por ${r.decliningSessions}+ sessões" +
                (if (lowReadiness) " e prontidão média baixa" else "") +
                ". Sugestão: revisar o ciclo — por exemplo, uma semana com ~${(r.volumeReduction * 100).roundToInt()}% menos séries, " +
                "mantendo os exercícios. A evidência sobre deloads programados é limitada; trate como ajuste, não obrigação."
        else "Sem sinal consistente de queda de desempenho que justifique revisar o ciclo agora."
        return DeloadAdvice(recommended, declining, Explanation.rule(text, rule.id))
    }
}
