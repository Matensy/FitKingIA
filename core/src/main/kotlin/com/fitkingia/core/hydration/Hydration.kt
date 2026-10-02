package com.fitkingia.core.hydration

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.HydrationRules
import com.fitkingia.core.knowledge.Rule
import com.fitkingia.core.model.SweatLevel
import java.time.LocalDate
import kotlin.math.roundToInt

data class HydrationInput(
    val weightKg: Double,
    val exerciseMinutes: Int = 0,
    val sweat: SweatLevel = SweatLevel.MODERATE,
    val hot: Boolean = false,
)

data class HydrationTarget(val totalMl: Int, val baseMl: Int, val exerciseMl: Int, val explanation: Explanation)

data class WaterLog(val date: LocalDate, val ml: Int)

data class HydrationProgress(val consumedMl: Int, val targetMl: Int, val pct: Int, val display: String)

/** Water Engine: meta diária estimada (heurística transparente) + registro (+250 / +500 ml). */
class HydrationEngine(private val rule: Rule<HydrationRules>) {

    fun target(input: HydrationInput): HydrationTarget {
        val r = rule.params
        val base = input.weightKg * r.mlPerKg
        val exercise = input.exerciseMinutes / 60.0 * (r.sweatMlPerHour[input.sweat] ?: 0) * (if (input.hot) r.hotMultiplier else 1.0)
        val round = { v: Double -> ((v / r.roundToMl).roundToInt() * r.roundToMl) }
        val total = round(base + exercise)
        return HydrationTarget(
            total, round(base), round(exercise),
            Explanation.rule(
                "Meta estimada: ${ml(round(base))} ml (${r.mlPerKg.roundToInt()} ml/kg) + ${ml(round(exercise))} ml pelo treino " +
                    "(${input.exerciseMinutes} min, ${input.sweat.label.lowercase()}${if (input.hot) ", calor" else ""}). " +
                    "A necessidade real varia muito entre pessoas; pesar-se antes e depois do treino ajuda a estimar " +
                    "sua taxa de suor. Quem tem condição renal/cardíaca deve seguir orientação médica.",
                rule.id,
            ),
        )
    }

    fun progress(target: HydrationTarget, logs: List<WaterLog>, date: LocalDate): HydrationProgress {
        val consumed = logs.filter { it.date == date }.sumOf { it.ml }
        val pct = if (target.totalMl == 0) 0 else (consumed * 100.0 / target.totalMl).roundToInt()
        return HydrationProgress(consumed, target.totalMl, pct, "💧 ${ml(consumed)} / ${ml(target.totalMl)} ml")
    }

    private fun ml(v: Int) = com.fitkingia.core.model.Fmt.int(v)
}
