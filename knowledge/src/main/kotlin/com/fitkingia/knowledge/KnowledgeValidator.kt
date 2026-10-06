package com.fitkingia.knowledge

import com.fitkingia.core.knowledge.*
import com.fitkingia.core.model.*
import com.fitkingia.core.program.ProgramGenerator
import com.fitkingia.core.safety.SafetyScreening

enum class Severity { ERROR, WARNING }

data class Issue(val severity: Severity, val where: String, val message: String) {
    override fun toString() = "[$severity] $where: $message"
}

/**
 * Regras de integridade do conteúdo — o "lint" do banco de conhecimento. Erros quebram o build
 * (teste de integração); avisos aparecem no relatório para revisão humana.
 */
object KnowledgeValidator {

    /** Regras referenciadas diretamente pelo código (além das tipadas em RuleSet). */
    val CODE_RULES = listOf(ProgramGenerator.SPLIT_RULE, ProgramGenerator.SELECTION_RULE, RuleId("substitution.scoring"), SafetyScreening.RULE)

    fun validate(kb: KnowledgeBase): List<Issue> {
        val out = mutableListOf<Issue>()
        fun err(w: String, m: String) = out.add(Issue(Severity.ERROR, w, m))
        fun warn(w: String, m: String) = out.add(Issue(Severity.WARNING, w, m))

        // Evidências: toda regra baseada em evidência aponta para afirmações; toda afirmação tem fonte que a sustenta.
        for (r in kb.rules) {
            if (r.basis == RuleBasis.EVIDENCE && r.claimIds.isEmpty()) err("rule ${r.id}", "regra EVIDENCE sem afirmações vinculadas")
            if (r.rationale.isBlank()) err("rule ${r.id}", "regra sem justificativa")
        }
        for (c in kb.claims) {
            if (c.sources.none { it.stance == Stance.SUPPORTS }) err("claim ${c.id}", "afirmação sem fonte SUPPORTS")
            if (c.sources.any { kb.source(it.sourceId).verification == VerificationStatus.PENDING })
                warn("claim ${c.id}", "apoia-se em fonte com verificação pendente")
            if (c.isConflicting && c.evidenceLevel == EvidenceLevel.HIGH) warn("claim ${c.id}", "evidência conflitante marcada como alta")
        }
        val referenced = kb.claims.flatMap { c -> c.sources.map { it.sourceId } }.toSet() +
            kb.foods.map { it.sourceId } + kb.supplements.flatMap { it.sourceIds }
        for (s in kb.sources) {
            if (s.verification == VerificationStatus.VERIFIED && s.lastVerified == null) err("source ${s.id}", "VERIFIED sem data de verificação")
            if (s.url == null && s.doi == null) warn("source ${s.id}", "sem URL nem DOI")
            if (s.id !in referenced) warn("source ${s.id}", "fonte não usada por nenhuma afirmação, alimento ou suplemento")
        }
        for (id in CODE_RULES + allTypedRuleIds(kb)) if (kb.ruleOrNull(id) == null) err("rule $id", "regra usada pelo código não existe no banco")

        // Catálogo
        val fullGym = kb.environments.firstOrNull { it.id == EnvironmentId("full_gym") }?.equipment ?: emptySet()
        for (sp in kb.splits) for (se in sp.sessions) for ((i, sl) in se.slots.withIndex()) {
            val any = kb.exercises.any { it.pattern == sl.pattern && (sl.targetMuscle == null || sl.targetMuscle in it.primaryMuscles) && fullGym.containsAll(it.equipment) }
            if (!any) err("split ${sp.id}/${se.key}#$i", "nenhum exercício atende ${sl.pattern}${sl.targetMuscle?.let { "/$it" } ?: ""} na academia completa")
        }
        for (r in BodyRegion.values()) {
            if (kb.trackedMuscles.none { it.focusRegion == r }) err("region $r", "região priorizável sem músculo rastreado")
        }
        for (m in kb.trackedMuscles) {
            if (kb.exercises.none { m.id in it.primaryMuscles }) err("muscle ${m.id}", "músculo rastreado sem exercício direto")
            if (m.fillPattern == null) warn("muscle ${m.id}", "músculo rastreado sem padrão de preenchimento")
        }
        for (e in kb.exercises) {
            if (e.primaryMuscles.isEmpty()) err("exercise ${e.id}", "sem músculo principal")
            if ((e.primaryMuscles intersect e.secondaryMuscles).isNotEmpty()) err("exercise ${e.id}", "músculo listado como principal e secundário")
            if (!e.primaryMuscles.containsAll(e.focusMuscles)) err("exercise ${e.id}", "foco ${e.focusMuscles} fora dos músculos principais")
            if (e.instructions.isEmpty()) warn("exercise ${e.id}", "sem instruções")
            for (sub in e.curatedSubstitutes) {
                val s = kb.exerciseOrNull(sub) ?: run { err("exercise ${e.id}", "substituto inexistente $sub"); continue }
                val samePattern = s.pattern == e.pattern || s.pattern in kb.pattern(e.pattern).related
                if (!samePattern && (s.primaryMuscles intersect e.primaryMuscles).isEmpty())
                    warn("exercise ${e.id}", "substituto ${s.id} não compartilha padrão nem músculo principal")
            }
        }
        // Cada ambiente com equipamento deve conseguir montar pelo menos os padrões básicos.
        for (env in kb.environments) for (p in listOf("squat", "horizontal_push", "horizontal_pull", "hinge")) {
            if (kb.exercises.none { it.pattern == PatternId(p) && env.equipment.containsAll(it.equipment) })
                warn("environment ${env.id}", "sem exercício de $p")
        }
        // Alimentos: sem valores negativos; kcal coerente com macros quando todos existem (Atwater 4/4/9, tolerância 15%).
        for (f in kb.foods) {
            val p = f.proteinG; val c = f.carbsG; val fat = f.fatG
            if (p != null && c != null && fat != null) {
                val atwater = p * 4 + c * 4 + fat * 9
                if (atwater > 0 && kotlin.math.abs(atwater - f.kcal) / f.kcal > 0.15)
                    warn("food ${f.id}", "kcal ${f.kcal} difere de 4P+4C+9G = ${"%.0f".format(atwater)} em mais de 15%")
            }
        }
        return out
    }

    private fun allTypedRuleIds(kb: KnowledgeBase): List<RuleId> = with(kb.ruleSet) {
        listOf(volume.id, prescription.id, frequency.id, timing.id, progression.id, recovery.id, fatigue.id, scheduling.id,
            hydration.id, nutrition.id, deload.id, weightTrend.id, bodyMetrics.id, gamification.id, priority.id)
    }
}
