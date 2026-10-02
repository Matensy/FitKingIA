package com.fitkingia.core.safety

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.SafetyOutcome
import com.fitkingia.core.knowledge.SafetyQuestion
import com.fitkingia.core.model.RuleId
import com.fitkingia.core.model.Sex
import com.fitkingia.core.model.UserProfile

enum class ScreeningStatus(val label: String) {
    /** Sem sinais de alerta: prescrição normal. */
    CLEAR("Liberado"),
    /** Gera programa, com avisos e restrições (ex.: dor articular → filtros de exercício). */
    CAUTION("Liberado com cautela"),
    /** Não gera prescrição normal: orientar procura de profissional de saúde. */
    REFER("Procure um profissional antes"),
    /** Questionário incompleto. */
    INCOMPLETE("Questionário incompleto"),
}

data class ScreeningResult(
    val status: ScreeningStatus,
    val messages: List<Explanation>,
    val flagged: List<SafetyQuestion>,
    val unanswered: List<SafetyQuestion>,
) {
    val allowsProgram: Boolean get() = status == ScreeningStatus.CLEAR || status == ScreeningStatus.CAUTION
}

/**
 * Triagem pré-participação inspirada nos princípios da ACSM (sinais/sintomas, doença conhecida,
 * nível de atividade). As perguntas ficam no banco (`safety_questions`) com redação própria.
 * O app não diagnostica: red flags interrompem a prescrição e orientam avaliação profissional.
 */
class SafetyScreening(private val kb: KnowledgeBase) {

    fun questionsFor(sex: Sex): List<SafetyQuestion> =
        kb.safetyQuestions.filter { it.appliesTo == null || it.appliesTo == sex }

    fun evaluate(profile: UserProfile, answers: Map<String, Boolean>): ScreeningResult {
        val questions = questionsFor(profile.sex)
        val unanswered = questions.filter { it.id !in answers }
        val flagged = questions.filter { answers[it.id] == true }
        val messages = mutableListOf<Explanation>()

        if (profile.age < 18) {
            messages += Explanation.rule(
                "O FitKingIA foi desenhado para adultos (18+). Crianças e adolescentes têm recomendações próprias " +
                    "(OMS) e devem ser acompanhados por profissionais.", RULE,
            )
            return ScreeningResult(ScreeningStatus.REFER, messages, flagged, unanswered)
        }
        if (unanswered.isNotEmpty()) {
            messages += Explanation.rule("Responda todas as perguntas de segurança antes de gerar o primeiro treino.", RULE)
            return ScreeningResult(ScreeningStatus.INCOMPLETE, messages, flagged, unanswered)
        }

        val refer = flagged.filter { it.outcomeIfYes == SafetyOutcome.BLOCK }
        val caution = flagged.filter { it.outcomeIfYes == SafetyOutcome.CAUTION }
        flagged.forEach { messages += Explanation.rule(it.message, RULE) }

        val severePain = profile.limitations.filter { it.severity >= 7 }
        severePain.forEach {
            messages += Explanation.rule(
                "Dor intensa relatada (${it.joint.label}, ${it.severity}/10). Exercícios que exigem essa articulação " +
                    "foram excluídos. Procure avaliação profissional — o app não diagnostica.", RULE,
            )
        }

        val status = when {
            refer.isNotEmpty() -> ScreeningStatus.REFER
            caution.isNotEmpty() || profile.limitations.isNotEmpty() -> ScreeningStatus.CAUTION
            else -> ScreeningStatus.CLEAR
        }
        if (status == ScreeningStatus.REFER) messages += Explanation.rule(
            "⚠️ O aplicativo não vai gerar uma prescrição normal. Converse com um profissional de saúde antes de " +
                "iniciar ou modificar seu programa.", RULE,
        )
        return ScreeningResult(status, messages, flagged, unanswered)
    }

    companion object {
        val RULE = RuleId("safety.screening")
    }
}
