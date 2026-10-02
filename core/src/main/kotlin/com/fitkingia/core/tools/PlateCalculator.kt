package com.fitkingia.core.tools

import kotlin.math.abs
import kotlin.math.roundToInt

data class PlateLoad(
    val targetKg: Double,
    val barKg: Double,
    /** Anilhas em UM lado da barra, da maior para a menor. */
    val perSide: List<Double>,
    val achievedKg: Double,
) {
    val exact: Boolean get() = abs(achievedKg - targetKg) < 1e-9
    fun describe(): String {
        val side = if (perSide.isEmpty()) "nenhuma anilha" else perSide.joinToString(" + ") { kg(it) }
        val head = "Barra ${kg(barKg)} + por lado: $side = ${kg(achievedKg)}"
        return if (exact) head else "$head (mais próximo possível de ${kg(targetKg)})"
    }
    private fun kg(v: Double) = (if (v % 1.0 == 0.0) v.toInt().toString() else v.toString().replace('.', ',')) + " kg"
}

/**
 * Plate Calculator. Resolve com programação dinâmica (não guloso), respeitando quantas
 * anilhas de cada peso existem, e minimiza o número de anilhas. Ex.: 82,5 kg com barra
 * de 20 kg = 31,25 kg por lado → 25 + 5 + 1,25.
 */
object PlateCalculator {
    /** Pares disponíveis por peso (padrão de academia). */
    val DEFAULT_PLATES: Map<Double, Int> = linkedMapOf(25.0 to 4, 20.0 to 4, 15.0 to 2, 10.0 to 2, 5.0 to 2, 2.5 to 2, 1.25 to 2)

    fun compute(targetKg: Double, barKg: Double = 20.0, platePairs: Map<Double, Int> = DEFAULT_PLATES): PlateLoad {
        require(targetKg >= 0 && barKg >= 0)
        if (targetKg <= barKg) return PlateLoad(targetKg, barKg, emptyList(), barKg)
        val unit = 0.25 // trabalha em múltiplos de 250 g
        val sideUnits = ((targetKg - barKg) / 2 / unit).roundToInt()
        val pieces = platePairs.entries.sortedByDescending { it.key }
            .flatMap { (w, n) -> List(n) { (w / unit).roundToInt() to w } }
        // best[u] = menor nº de anilhas para somar exatamente u; via mochila 0/1 sobre as peças.
        val inf = Int.MAX_VALUE / 2
        val best = IntArray(sideUnits + 1) { inf }.also { it[0] = 0 }
        val choice = Array(sideUnits + 1) { emptyList<Double>() }
        for ((u, w) in pieces) {
            for (s in sideUnits downTo u) {
                if (best[s - u] >= inf) continue
                val count = best[s - u] + 1
                val candidate = (choice[s - u] + w).sortedDescending()
                // Mesmo nº de anilhas: prefere a combinação com anilhas maiores (ex.: 25+5 em vez de 15+15).
                if (count < best[s] || (count == best[s] && heavierFirst(candidate, choice[s]))) {
                    best[s] = count
                    choice[s] = candidate
                }
            }
        }
        val reach = (sideUnits downTo 0).first { best[it] < inf }
        val side = choice[reach].sortedDescending()
        return PlateLoad(targetKg, barKg, side, barKg + 2 * side.sum())
    }

    private fun heavierFirst(a: List<Double>, b: List<Double>): Boolean {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
}
