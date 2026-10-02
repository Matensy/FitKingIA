package com.fitkingia.core.progression

import kotlin.math.roundToInt

/**
 * Estimativas de 1RM (Epley e Brzycki). São aproximações: a precisão cai com muitas
 * repetições e varia por exercício (LeSuer et al., 1997). Com RIR informado, usamos
 * repetições até a falha estimadas = reps + RIR.
 */
object OneRepMax {
    data class Estimate(val epley: Double, val brzycki: Double, val average: Double, val reliable: Boolean, val note: String?)

    fun epley(load: Double, reps: Int): Double = if (reps <= 1) load else load * (1 + reps / 30.0)

    fun brzycki(load: Double, reps: Int): Double {
        require(reps < 37) { "Brzycki não é definida para 37+ repetições" }
        return if (reps <= 1) load else load * 36.0 / (37.0 - reps)
    }

    fun estimate(load: Double, reps: Int, rir: Int? = null, maxReliableReps: Int = 10): Estimate {
        require(reps >= 1) { "precisa de ao menos 1 repetição" }
        val toFailure = (reps + (rir ?: 0)).coerceAtMost(36)
        val e = epley(load, toFailure)
        val b = brzycki(load, toFailure)
        val reliable = toFailure <= maxReliableReps
        return Estimate(
            round1(e), round1(b), round1((e + b) / 2), reliable,
            if (reliable) null else "Estimativa menos confiável acima de $maxReliableReps repetições até a falha.",
        )
    }

    /** Melhor e1RM (Epley) da sessão, usado para tendência e PR. Nulo para séries sem carga. */
    fun bestOf(log: ExerciseLog): Double? =
        log.sets.filter { it.loadKg > 0 && it.reps >= 1 }
            .maxOfOrNull { epley(it.loadKg, (it.reps + (it.rir ?: 0)).coerceAtMost(36)) }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}
