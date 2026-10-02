package com.fitkingia.core.tools

import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.model.LoadType
import kotlin.math.roundToInt

data class WarmupSet(val loadKg: Double, val reps: Int, val note: String)

/**
 * Séries de aquecimento específicas antes do exercício principal (prática comum de
 * aproximação progressiva; heurística do sistema, evidência limitada sobre o esquema ideal).
 */
object WarmupGenerator {
    fun forWorkingLoad(exercise: Exercise, workingKg: Double, barKg: Double = 20.0, step: Double = 2.5): List<WarmupSet> {
        if (exercise.loadType == LoadType.BODYWEIGHT || exercise.loadType == LoadType.BAND || workingKg <= 0) {
            return listOf(WarmupSet(0.0, 6, "1 série leve, longe da falha, para ensaiar o movimento"))
        }
        val scheme = when (exercise.loadType) {
            LoadType.BARBELL, LoadType.SMITH -> listOf(0.4 to 8, 0.6 to 5, 0.8 to 3)
            else -> listOf(0.5 to 8, 0.75 to 4)
        }
        val out = mutableListOf<WarmupSet>()
        if (exercise.loadType == LoadType.BARBELL && workingKg >= barKg * 2) out += WarmupSet(barKg, 10, "só a barra")
        for ((pct, reps) in scheme) {
            val load = ((workingKg * pct) / step).roundToInt() * step
            val floor = if (exercise.loadType == LoadType.BARBELL) barKg else 0.0
            if (load <= floor || load >= workingKg || (out.isNotEmpty() && load <= out.last().loadKg)) continue
            out += WarmupSet(load, reps, "${(pct * 100).roundToInt()}% da carga de trabalho")
        }
        return out
    }
}
