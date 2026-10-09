package com.fitkingia.core.explain

import com.fitkingia.core.knowledge.*
import com.fitkingia.core.model.*
import com.fitkingia.core.program.Program
import com.fitkingia.core.program.ProgramGenerator
import com.fitkingia.core.program.pt
import java.time.DayOfWeek
import java.time.LocalDate

data class SourceRef(
    val citation: String,
    val url: String?,
    val type: String,
    val tier: SourceTier,
    val lastVerified: LocalDate?,
    val verification: VerificationStatus,
)

data class EvidenceItem(
    val statement: String,
    val level: EvidenceLevel,
    val conflicting: Boolean,
    val supports: List<SourceRef>,
    val contradicts: List<SourceRef>,
    val context: List<SourceRef>,
    val lastReviewed: LocalDate,
)

data class RuleItem(
    val id: RuleId,
    val description: String,
    val basis: RuleBasis,
    val rationale: String,
    val lastReviewed: LocalDate,
    val evidence: List<EvidenceItem>,
)

/** Resposta do botão "🔬 Por que isso?" — cadeia recomendação → regra → evidência → fonte. */
data class WhyReport(val title: String, val lines: List<String>, val rules: List<RuleItem>)

class WhyService(private val kb: KnowledgeBase) {

    fun claim(id: ClaimId): EvidenceItem {
        val c = kb.claim(id)
        fun refs(st: Stance) = c.sources.filter { it.stance == st }.map { ref(kb.source(it.sourceId)) }
        return EvidenceItem(c.statement, c.evidenceLevel, c.isConflicting, refs(Stance.SUPPORTS), refs(Stance.CONTRADICTS), refs(Stance.CONTEXT), c.lastReviewed)
    }

    fun rule(id: RuleId): RuleItem? = kb.ruleOrNull(id)?.let { r ->
        RuleItem(r.id, r.description, r.basis, r.rationale, r.lastReviewed, r.claimIds.map { claim(it) })
    }

    fun forExercise(program: Program, exerciseId: ExerciseId, day: DayOfWeek? = null): WhyReport {
        val session = program.sessions.firstOrNull { s -> (day == null || s.day == day) && s.exercises.any { it.exercise.id == exerciseId } }
            ?: error("Exercício $exerciseId não está no programa" + (day?.let { " em ${it.pt()}" } ?: ""))
        val pe = session.exercises.first { it.exercise.id == exerciseId }
        val p = pe.prescription
        val ex = pe.exercise
        val lines = mutableListOf(
            "Objetivo: ${program.focus.label.lowercase()}",
            "Sessão: ${session.name} (${session.day?.pt() ?: "sem dia"}) — papel ${pe.role.label.lowercase()}",
            "Prescrição: ${pe.sets} × ${p.target}, RIR ${p.rir}, descanso ${sec(p.restSeconds.first)}–${sec(p.restSeconds.last)}, ${p.tempo}",
            "Padrão de movimento: ${kb.patternName(ex.pattern).lowercase()}; músculos principais: ${ex.primaryMuscles.joinToString { kb.muscleName(it).lowercase() }}",
        )
        for (m in ex.primaryMuscles) {
            val t = program.volumeTargets[m] ?: continue
            lines += "Volume semanal considerado (${kb.muscleName(m).lowercase()}): ${ProgramGenerator.fmt(program.weeklyVolume[m] ?: 0.0)} séries " +
                "(faixa ${ProgramGenerator.fmt(t.min)}–${ProgramGenerator.fmt(t.max)})"
        }
        lines += "Base de conhecimento: versão ${kb.meta["content_version"] ?: "?"}, revisão ${kb.meta["last_reviewed"] ?: "?"}"
        val rules = listOf(kb.ruleSet.prescription.id, kb.ruleSet.volume.id, ProgramGenerator.SELECTION_RULE, kb.ruleSet.progression.id)
            .mapNotNull { rule(it) }
        return WhyReport("🔬 Por que ${ex.name}: ${pe.sets} × ${p.target}?", lines, rules)
    }

    /** "Por que faço costas segunda?" */
    fun forDay(program: Program, day: DayOfWeek): WhyReport {
        val s = program.sessionOn(day) ?: return WhyReport(
            "Por que não treino ${day.pt()}?",
            listOf("${day.pt().replaceFirstChar { it.uppercase() }} não recebeu sessão: o dia não estava disponível, tinha pouco tempo, " +
                "ou foi deixado livre para espaçar os treinos."),
            listOfNotNull(rule(kb.ruleSet.frequency.id), rule(kb.ruleSet.scheduling.id)),
        )
        val days = program.trainingDays.sorted().joinToString { it.pt() }
        val muscles = s.exercises.flatMap { it.exercise.primaryMuscles }.distinct().joinToString { kb.muscleName(it).lowercase() }
        return WhyReport(
            "Por que ${s.name} ${if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) "no" else "na"} ${day.pt()}?",
            listOf(
                "Sua divisão (${program.split.name}) foi montada dessa forma porque seus dias de treino são $days, " +
                    "e o algoritmo buscou distribuir os grupos musculares e a recuperação entre as sessões.",
                "Músculos principais desta sessão: $muscles.",
                "Tempo disponível no dia: ${s.budgetMinutes ?: "?"} min; sessão estimada: ${s.estimatedMinutes} min.",
            ),
            listOfNotNull(rule(ProgramGenerator.SPLIT_RULE), rule(kb.ruleSet.scheduling.id)),
        )
    }

    private fun ref(s: EvidenceSource) = SourceRef(
        s.citation(), s.url ?: s.doi?.let { "https://doi.org/$it" }, s.type.label, s.tier, s.lastVerified, s.verification,
    )

    private fun sec(v: Int) = if (v % 60 == 0) "${v / 60} min" else "${v}s"
}
