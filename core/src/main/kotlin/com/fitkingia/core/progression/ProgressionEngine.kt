package com.fitkingia.core.progression

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.RepPrescription
import com.fitkingia.core.model.LoadType
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class ProgressionAction(val label: String) {
    START("Primeira sessão"),
    INCREASE_LOAD("Aumentar carga"),
    HOLD("Manter carga e buscar repetições"),
    CONSOLIDATE("Manter carga e consolidar"),
    DECREASE_LOAD("Reduzir carga"),
    PROGRESS_BODYWEIGHT("Progredir peso corporal"),
}

data class ProgressionSuggestion(
    val action: ProgressionAction,
    val suggestedLoadKg: Double?,
    val repTargets: List<Int>,
    val message: String,
    /** "Você normalmente completa 10–12 repetições com esta carga." */
    val pattern: String?,
    val explanation: Explanation,
)

/**
 * Progressive Overload Engine — dupla progressão: dentro da faixa de repetições, busque
 * mais repetições; quando todas as séries atingem o topo com o RIR adequado, aumente a carga.
 * Toda sugestão se apoia no histórico do próprio usuário e diz isso explicitamente.
 */
class ProgressionEngine(private val kb: KnowledgeBase) {
    private val rule = kb.ruleSet.progression

    fun suggest(exercise: Exercise, prescription: RepPrescription, plannedSets: Int, history: List<ExerciseLog>): ProgressionSuggestion {
        val p = rule.params
        val range = prescription.reps
        val logs = history.filter { it.exerciseId == exercise.id && it.sets.isNotEmpty() }.sortedBy { it.date }
        val hold = prescription.holdSeconds
        // Exercício em tempo (prancha…): começa pela duração, não por "carga que permita N repetições".
        if (logs.isEmpty() && exercise.timed && hold != null) return ProgressionSuggestion(
            ProgressionAction.START, null, List(plannedSets) { range.last }, "Primeira vez neste exercício: sustente " +
                "${hold.first} s por série com boa técnica e suba até ${hold.last} s. A próxima sugestão virá dos seus registros.", null,
            Explanation.rule("Sem histórico para ${exercise.name}; começa pelo início da faixa de tempo.", rule.id),
        )
        val last = logs.lastOrNull()
            ?: return ProgressionSuggestion(
                ProgressionAction.START, null, List(plannedSets) { range.last }, "Primeira vez neste exercício: escolha uma carga " +
                    "que permita ${range.last} repetições terminando com ~${prescription.rir + 1} repetições sobrando. " +
                    "A próxima sugestão virá dos seus registros.", null,
                Explanation.rule("Sem histórico para ${exercise.name}; carga inicial é autosselecionada pelo RIR.", rule.id),
            )

        if (exercise.timed) return suggestion(
            ProgressionAction.PROGRESS_BODYWEIGHT, null, emptyList(),
            "Exercício em tempo: aumente a duração até o topo da faixa (${prescription.holdSeconds?.last ?: 45} s) em todas as séries; " +
                "depois use uma variação mais difícil ou carga.", null,
            "Sugestão baseada nas suas sessões anteriores (${logs.size} registro(s)).",
        )
        val working = last.workingSets
        val load = last.workingLoad
        val pattern = typicalPattern(logs, load)
        val basis = "Sugestão baseada nas suas sessões anteriores: última sessão ${fmtKg(load)} — " +
            working.joinToString(" / ") { it.reps.toString() } +
            (working.mapNotNull { it.rir }.takeIf { it.isNotEmpty() }?.let { " (RIR ${it.joinToString("/")})" } ?: "") +
            "; meta ${range.first}–${range.last} repetições, RIR ${prescription.rir}."

        val allTop = working.size >= plannedSets && working.all { it.reps >= range.last }
        val rirs = working.mapNotNull { it.rir }
        val rirOk = rirs.isEmpty() || rirs.all { it >= prescription.rir - 1 }
        val tooEasy = rirs.size == working.size && rirs.isNotEmpty() && rirs.all { it >= prescription.rir + p.easyRirMargin }
        val belowMin = working.count { it.reps < range.first } >= ceil(plannedSets / 2.0).toInt()

        if (exercise.loadType == LoadType.BODYWEIGHT && load == 0.0) {
            return if (allTop) suggestion(
                ProgressionAction.PROGRESS_BODYWEIGHT, null, List(plannedSets) { range.last },
                "Você atingiu o topo da faixa com o peso corporal. Próximo passo: adicionar carga externa (colete/anilha), " +
                    "usar uma variação mais difícil ou desacelerar a fase excêntrica.", pattern, basis,
            ) else suggestion(ProgressionAction.HOLD, null, nextReps(working, range, plannedSets),
                "Busque mais repetições até ${range.last} em todas as séries.", pattern, basis)
        }

        val inc = p.increments[exercise.loadType] ?: 2.5
        return when {
            allTop && rirOk -> {
                val step = if (tooEasy) inc * 2 else inc
                val next = roundTo(load + step, inc)
                suggestion(
                    ProgressionAction.INCREASE_LOAD, next, List(plannedSets) { range.first },
                    "Sugestão para hoje: +${fmtKg(next - load)} (${fmtKg(next)}). Volte ao início da faixa (${range.first} repetições) e suba de novo." +
                        if (tooEasy) " As séries terminaram bem longe da falha, então o salto é duplo." else "",
                    pattern, basis,
                )
            }
            allTop -> suggestion(
                ProgressionAction.CONSOLIDATE, load, List(plannedSets) { range.last },
                "Você atingiu ${range.last} repetições, mas mais perto da falha que o planejado (RIR ${prescription.rir}). " +
                    "Mantenha ${fmtKg(load)} até repetir o desempenho com mais folga.", pattern, basis,
            )
            belowMin -> {
                val streak = trailingBelowMin(logs, range.first, plannedSets)
                if (streak >= p.failedSessionsBeforeReduction) {
                    val next = roundTo(load * (1 - p.reductionFraction), inc).coerceAtLeast(inc)
                    suggestion(
                        ProgressionAction.DECREASE_LOAD, next, List(plannedSets) { range.first },
                        "$streak sessões seguidas abaixo de ${range.first} repetições com ${fmtKg(load)}. Sugestão: ${fmtKg(next)} " +
                            "e reconstruir a partir do início da faixa.", pattern, basis,
                    )
                } else suggestion(
                    ProgressionAction.HOLD, load, nextReps(working, range, plannedSets),
                    "Abaixo da faixa nesta sessão. Mantenha ${fmtKg(load)} e busque ${range.first} repetições ou mais; " +
                        "se repetir na próxima, a carga será reduzida.", pattern, basis,
                )
            }
            else -> suggestion(
                ProgressionAction.HOLD, load, nextReps(working, range, plannedSets),
                "Mantenha ${fmtKg(load)} e busque " + nextReps(working, range, plannedSets).joinToString("/") + " repetições.",
                pattern, basis,
            )
        }
    }

    private fun suggestion(a: ProgressionAction, load: Double?, reps: List<Int>, msg: String, pattern: String?, basis: String) =
        ProgressionSuggestion(a, load, reps, msg, pattern, Explanation.rule(basis, rule.id))

    private fun nextReps(working: List<SetLog>, range: IntRange, sets: Int): List<Int> =
        (0 until sets).map { i -> ((working.getOrNull(i)?.reps ?: range.first) + 1).coerceIn(range.first, range.last) }

    private fun trailingBelowMin(logs: List<ExerciseLog>, min: Int, sets: Int): Int {
        val load = logs.last().workingLoad
        var n = 0
        for (log in logs.asReversed()) {
            if (log.workingLoad != load) break
            if (log.workingSets.count { it.reps < min } >= ceil(sets / 2.0).toInt()) n++ else break
        }
        return n
    }

    /** Aprende o padrão do usuário com a carga atual (últimas sessões com a mesma carga). */
    private fun typicalPattern(logs: List<ExerciseLog>, load: Double): String? {
        val same = logs.filter { it.workingLoad == load }.takeLast(4)
        if (same.size < 2) return null
        val reps = same.flatMap { l -> l.workingSets.map { it.reps } }
        return "Você normalmente completa ${reps.min()}–${reps.max()} repetições com ${fmtKg(load)} (${same.size} sessões)."
    }

    companion object {
        fun roundTo(v: Double, step: Double): Double = (v / step).roundToInt() * step
        fun fmtKg(v: Double): String = com.fitkingia.core.model.Fmt.kg(v)
    }
}
