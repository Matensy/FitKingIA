package com.fitkingia.coach

import com.fitkingia.coach.nlu.Intent
import com.fitkingia.core.body.BodyMeasurement
import com.fitkingia.core.body.WeightEntry
import com.fitkingia.core.hydration.WaterLog
import com.fitkingia.core.model.ExerciseId
import com.fitkingia.core.model.UserProfile
import com.fitkingia.core.program.Program
import com.fitkingia.core.progression.ExerciseLog
import com.fitkingia.core.safety.ScreeningResult
import java.time.DayOfWeek
import java.time.LocalDate

/** Tudo que a IA local pode consultar. Fica no aparelho: nada é enviado para servidor. */
data class CoachContext(
    val profile: UserProfile,
    val screening: ScreeningResult,
    val program: Program?,
    val today: LocalDate,
    val history: List<ExerciseLog> = emptyList(),
    val measurements: List<BodyMeasurement> = emptyList(),
    val weights: List<WeightEntry> = emptyList(),
    val water: List<WaterLog> = emptyList(),
)

/** Ações que a IA propõe e o app executa (e grava no user.db) — a IA não escreve dados sozinha. */
sealed interface CoachAction {
    data class LogWater(val ml: Int) : CoachAction
    data class LogMeal(val text: String, val kcal: Int, val proteinG: Int) : CoachAction
}

data class CoachReply(
    val text: String,
    val intent: Intent?,
    val confidence: Double,
    val quickReplies: List<String> = emptyList(),
    val actions: List<CoachAction> = emptyList(),
)

/** Memória curta da conversa: permite "e esse?", "pode trocar?" e responder perguntas de esclarecimento. */
class ConversationState {
    var lastExercise: ExerciseId? = null
    var lastDay: DayOfWeek? = null
    var lastIntent: Intent? = null
    internal var pending: Pending? = null
    val awaitingAnswer: Boolean get() = pending != null
}

internal enum class Slot(val question: String) {
    EXERCISE("Qual exercício?"),
    MINUTES("Quanto tempo você tem hoje? (ex.: 35 minutos)"),
    JOINT("Onde é a dor? (ex.: joelho, ombro, lombar)"),
    MUSCLE("Para qual músculo? (ex.: peito, costas, quadríceps, glúteos)"),
    KG("Quantos quilos no total? (ex.: 82,5 kg)"),
    KG_REPS("Me diga carga e repetições, ex.: 100 kg x 5"),
    DAY("De qual dia? (ex.: quarta, ontem)"),
}

internal sealed interface Pending {
    val original: String
    data class AwaitSlot(val intent: Intent, val slot: Slot, override val original: String) : Pending
    data class Clarify(val options: List<Intent>, override val original: String) : Pending
}
