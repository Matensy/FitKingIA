package com.fitkingia.core.recovery

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.RecoveryRules
import com.fitkingia.core.knowledge.Rule
import com.fitkingia.core.model.SleepQuality
import kotlin.math.roundToInt

/** Check-in antes do treino ("Como você está?"). */
data class ReadinessCheck(
    val sleep: SleepQuality,
    /** 1–10 */
    val energy: Int,
    /** 0–10 */
    val soreness: Int,
    /** 1–10 */
    val stress: Int,
    /** 1–10 */
    val motivation: Int,
) {
    init {
        require(energy in 1..10) { "energia deve estar entre 1 e 10" }
        require(soreness in 0..10) { "dor muscular deve estar entre 0 e 10" }
        require(stress in 1..10) { "estresse deve estar entre 1 e 10" }
        require(motivation in 1..10) { "motivação deve estar entre 1 e 10" }
    }
}

enum class ReadinessBand(val label: String) {
    NORMAL("Treino normal"),
    REDUCED("Reduzir um pouco"),
    LOW("Reduzir bastante"),
    VERY_LOW("Sessão leve ou descanso"),
}

data class ReadinessResult(val score: Int, val band: ReadinessBand, val explanation: Explanation)

/**
 * Recovery Score 0–100. É uma heurística transparente do sistema (média ponderada das
 * respostas), não um índice fisiológico validado — e o app diz isso ao usuário.
 */
class RecoveryScorer(private val rule: Rule<RecoveryRules>) {
    fun score(c: ReadinessCheck): ReadinessResult {
        val r = rule.params
        val sleep = r.sleepScores[c.sleep] ?: 0.5
        val energy = (c.energy - 1) / 9.0
        val soreness = 1 - c.soreness / 10.0
        val stress = 1 - (c.stress - 1) / 9.0
        val motivation = (c.motivation - 1) / 9.0
        val wSum = r.weightSleep + r.weightEnergy + r.weightSoreness + r.weightStress + r.weightMotivation
        val raw = (r.weightSleep * sleep + r.weightEnergy * energy + r.weightSoreness * soreness +
            r.weightStress * stress + r.weightMotivation * motivation) / wSum
        val score = (raw * 100).roundToInt().coerceIn(0, 100)
        val band = when {
            score >= r.normalThreshold -> ReadinessBand.NORMAL
            score >= r.reducedThreshold -> ReadinessBand.REDUCED
            score >= r.lowThreshold -> ReadinessBand.LOW
            else -> ReadinessBand.VERY_LOW
        }
        return ReadinessResult(
            score, band,
            Explanation.rule(
                "Recovery Score $score/100 (${band.label.lowercase()}). Pontuação heurística do app a partir de sono, " +
                    "energia, dor muscular, estresse e motivação — útil para comparar seus próprios dias, não é medida clínica.",
                rule.id,
            ),
        )
    }
}
