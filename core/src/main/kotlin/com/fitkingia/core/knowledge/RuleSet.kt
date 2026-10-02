package com.fitkingia.core.knowledge

import com.fitkingia.core.model.*

/**
 * Parâmetros tipados das regras do motor. Os valores vêm do banco de conhecimento
 * (tabela `rules`), nunca de constantes espalhadas no código; cada bloco carrega o
 * [RuleId] para que qualquer decisão possa responder "por quê?".
 */
data class Rule<T>(val id: RuleId, val params: T)

data class RuleSet(
    val volume: Rule<VolumeRules>,
    val prescription: Rule<PrescriptionRules>,
    val frequency: Rule<FrequencyRules>,
    val timing: Rule<TimingRules>,
    val progression: Rule<ProgressionRules>,
    val recovery: Rule<RecoveryRules>,
    val fatigue: Rule<FatigueRules>,
    val scheduling: Rule<SchedulingRules>,
    val hydration: Rule<HydrationRules>,
    val nutrition: Rule<NutritionRules>,
    val deload: Rule<DeloadRules>,
    val weightTrend: Rule<WeightTrendRules>,
    val bodyMetrics: Rule<BodyMetricRules>,
    val gamification: Rule<GamificationRules>,
)

data class VolumeTarget(val min: Double, val target: Double, val max: Double) {
    fun scaled(f: Double) = VolumeTarget(min * f, target * f, max * f)
}

data class VolumeRules(
    val targets: Map<TrainingFocus, Map<TrainingTier, VolumeTarget>>,
    /** Contagem fracionada: série direta = 1,0; indireta = 0,5. */
    val primaryCredit: Double,
    val secondaryCredit: Double,
    val minSetsPerExercise: Int,
    val maxSetsPerExercise: Map<Mechanic, Int>,
) {
    fun target(focus: TrainingFocus, tier: TrainingTier): VolumeTarget =
        targets[focus]?.get(tier) ?: error("Sem meta de volume para $focus/$tier")
}

data class RepPrescription(
    val reps: IntRange,
    val rir: Int,
    val restSeconds: IntRange,
    val tempo: String,
    /** Preenchido para exercícios em tempo (prancha, carregamentos): a série é em segundos. */
    val holdSeconds: IntRange? = null,
) {
    /** "8–12" ou "20–45 s". */
    val target: String get() = holdSeconds?.let { "${it.first}–${it.last} s" } ?: "${reps.first}–${reps.last}"
}

data class PrescriptionRules(
    val byFocus: Map<TrainingFocus, Map<SlotRole, RepPrescription>>,
    /** Iniciantes treinam mais longe da falha enquanto aprendem a técnica. */
    val noviceExtraRir: Int,
    /** Duração por série dos exercícios em tempo. */
    val timedHoldSeconds: IntRange,
) {
    fun forRole(focus: TrainingFocus, role: SlotRole): RepPrescription =
        byFocus[focus]?.get(role) ?: error("Sem prescrição para $focus/$role")
}

data class FrequencyRules(
    val maxDaysByTier: Map<TrainingTier, Int>,
    /** Dias com menos tempo que isso não recebem sessão de musculação. */
    val minSessionMinutes: Int,
)

data class TimingRules(
    val generalWarmupMinutes: Int,
    /** Tempo extra das séries de aquecimento específicas do primeiro exercício principal. */
    val rampUpSeconds: Int,
    val workSecondsPerSet: Int,
    val setupSeconds: Map<LoadType, Int>,
)

data class ProgressionRules(
    /** Incremento padrão por tipo de carga (kg). Halteres: por halter. */
    val increments: Map<LoadType, Double>,
    /** Sessões seguidas abaixo da faixa antes de sugerir redução. */
    val failedSessionsBeforeReduction: Int,
    val reductionFraction: Double,
    /** RIR acima da meta + margem em todas as séries = carga leve demais (salto duplo). */
    val easyRirMargin: Int,
    /** Faixa de repetições em que estimativas de 1RM são consideradas razoáveis. */
    val maxRepsForE1rm: Int,
)

data class RecoveryRules(
    val weightSleep: Double,
    val weightEnergy: Double,
    val weightSoreness: Double,
    val weightStress: Double,
    val weightMotivation: Double,
    val sleepScores: Map<SleepQuality, Double>,
    val normalThreshold: Int,
    val reducedThreshold: Int,
    val lowThreshold: Int,
)

data class FatigueRules(
    val halfLifeHours: Double,
    val pointsPerHardSet: Double,
    val hardSetMaxRir: Int,
    val easySetFactor: Double,
    val sportPointsPerLevel: Double,
)

data class SchedulingRules(
    val consecutiveOverlapWeight: Double,
    val sportSameDayWeight: Double,
    val sportAdjacentWeight: Double,
    val overTimeWeight: Double,
    val orderDeviationWeight: Double,
)

data class HydrationRules(
    val mlPerKg: Double,
    val sweatMlPerHour: Map<SweatLevel, Int>,
    val hotMultiplier: Double,
    val roundToMl: Int,
)

data class NutritionRules(
    val activityFactors: Map<ActivityLevel, Double>,
    val energyAdjustment: Map<EnergyGoal, Double>,
    val proteinMinGPerKg: Double,
    val proteinTargetGPerKg: Double,
    val proteinMaxGPerKg: Double,
    val weeklyLossPctMin: Double,
    val weeklyLossPctMax: Double,
)

data class DeloadRules(
    val decliningSessions: Int,
    val minShareOfMainLifts: Double,
    val lowReadinessThreshold: Int,
    val volumeReduction: Double,
)

data class WeightTrendRules(val movingAverageDays: Int, val notableDailyChangeKg: Double)

data class BmiBand(val upperExclusive: Double, val label: String)

data class BodyMetricRules(
    val bmiBands: List<BmiBand>,
    val whtrIncreased: Double,
    val whtrHigh: Double,
)

data class GamificationRules(val points: Map<String, Int>, val xpPerLevel: Int)
