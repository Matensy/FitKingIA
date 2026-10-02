package com.fitkingia.core.progression

import com.fitkingia.core.model.ExerciseId
import java.time.LocalDate

/** Série realizada. Peso corporal: loadKg = carga externa adicional (0 se nenhuma). */
data class SetLog(val loadKg: Double, val reps: Int, val rir: Int? = null) {
    init {
        require(reps >= 0) { "repetições não podem ser negativas" }
        require(loadKg >= 0) { "carga não pode ser negativa" }
        require(rir == null || rir in 0..10) { "RIR deve estar entre 0 e 10" }
    }
    val volumeKg: Double get() = loadKg * reps
}

/** O que realmente aconteceu num exercício numa data. */
data class ExerciseLog(val exerciseId: ExerciseId, val date: LocalDate, val sets: List<SetLog>) {
    val topLoad: Double get() = sets.maxOfOrNull { it.loadKg } ?: 0.0
    val volumeKg: Double get() = sets.sumOf { it.volumeKg }

    /** Séries de trabalho = as feitas com a carga mais frequente da sessão (ignora aquecimento). */
    val workingSets: List<SetLog>
        get() {
            if (sets.isEmpty()) return emptyList()
            val mode = sets.groupingBy { it.loadKg }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<Double, Int>> { it.value }.thenByDescending { it.key })
                .first().key
            return sets.filter { it.loadKg == mode }
        }
    val workingLoad: Double get() = workingSets.firstOrNull()?.loadKg ?: 0.0
}
