package com.fitkingia.core.program

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.*
import com.fitkingia.core.model.*
import java.time.DayOfWeek

data class PlannedExercise(
    val exercise: Exercise,
    val role: SlotRole,
    val sets: Int,
    val prescription: RepPrescription,
    /** Descanso efetivamente planejado (dentro da faixa da prescrição). */
    val restSeconds: Int = (prescription.restSeconds.first + prescription.restSeconds.last) / 2,
    val slot: Slot? = null,
    val note: String? = null,
)

data class PlannedSession(
    val key: String,
    val name: String,
    val exercises: List<PlannedExercise>,
    val day: DayOfWeek? = null,
    val budgetMinutes: Int? = null,
    val estimatedMinutes: Int = 0,
)

data class Program(
    val split: SplitTemplate,
    val focus: TrainingFocus,
    val tier: TrainingTier,
    val sessions: List<PlannedSession>,
    val weeklyVolume: Map<MuscleId, Double>,
    val volumeTargets: Map<MuscleId, VolumeTarget>,
    val explanations: List<Explanation>,
    val warnings: List<Explanation>,
    /** Regiões priorizadas com que o programa foi gerado (as metas dependem disso). */
    val priorities: Set<BodyRegion> = emptySet(),
    /**
     * Checagem "seu objetivo × seu treino" feita na geração (avisos primeiro). Fica separada das
     * explicações: o app recalcula ao vivo (vale depois de trocas) com GoalAlignment.
     */
    val goalCheck: List<Explanation> = emptyList(),
) {
    val trainingDays: List<DayOfWeek> get() = sessions.mapNotNull { it.day }
    val weeklyMinutes: Int get() = sessions.sumOf { it.estimatedMinutes }
    fun sessionOn(day: DayOfWeek) = sessions.firstOrNull { it.day == day }
}

sealed interface ProgramResult {
    data class Generated(val program: Program) : ProgramResult
    /** O motor se recusa a gerar prescrição normal (ex.: red flag no questionário de segurança). */
    data class Refused(val reasons: List<Explanation>) : ProgramResult
}

private val dayNames = mapOf(
    DayOfWeek.MONDAY to "segunda", DayOfWeek.TUESDAY to "terça", DayOfWeek.WEDNESDAY to "quarta",
    DayOfWeek.THURSDAY to "quinta", DayOfWeek.FRIDAY to "sexta", DayOfWeek.SATURDAY to "sábado",
    DayOfWeek.SUNDAY to "domingo",
)

fun DayOfWeek.pt(): String = dayNames.getValue(this)
fun DayOfWeek.ptCapitalized(): String = pt().replaceFirstChar { it.uppercase() }
