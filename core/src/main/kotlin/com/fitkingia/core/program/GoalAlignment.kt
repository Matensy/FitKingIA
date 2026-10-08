package com.fitkingia.core.program

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.explain.Provenance
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*

/** Como uma região está sendo treinada no programa. */
data class RegionCoverage(
    val region: BodyRegion,
    val priority: Boolean,
    /** F — sessões da semana com exercício (≥ 2 séries) que tem um músculo da região como principal. */
    val frequency: Int,
    /** Frequência mínima da regra para a região, limitada pelos dias de treino. */
    val minFrequency: Int,
    /** E — séries/semana em exercícios com FOCO na região (curadoria: elevação pélvica foca glúteos; agachamento, quadríceps). */
    val focusedSets: Int,
    /** Alvo normal (sem prioridade) por músculo da região, para comparar as séries focadas. */
    val normalTarget: Double,
    /**
     * Cada músculo rastreado da região tem séries focadas ≥ alvo normal × min_focused_of_target. Por músculo, e
     * não somando a região: uma remada conta para latíssimo e dorsal média, mas é uma série só.
     */
    val focusedOk: Boolean,
    /** Músculos rastreados da região abaixo do mínimo planejado, ex.: "quadríceps 0 de 9". */
    val musclesBelowMin: List<String>,
    /** V — séries/semana com contagem fracionada (média dos músculos rastreados da região). */
    val weeklySets: Double,
    /** S — séries focadas ÷ séries da metade do corpo (inferiores para glúteos/pernas; superiores para o resto). */
    val shareOfHalf: Double,
    val minShare: Double,
    /** P — nas sessões com composto da região, quantas começam por ela. */
    val leadingSessions: Int,
    val sessionsWithCompound: Int,
    val ok: Boolean,
)

data class AlignmentReport(
    val coverage: List<RegionCoverage>,
    val lines: List<Explanation>,
    val warnings: List<Explanation>,
    /** Treinos na semana (base das frequências). */
    val sessions: Int,
)

/**
 * Checagem "seu objetivo × seu treino": confere, no programa já gerado, se a região que a pessoa
 * priorizou recebe frequência, séries focadas, participação e posição — e diz isso em números. Exemplo:
 * pernas 3× feitas só de agachamento e extensora NÃO contam como treino de glúteo (pouca série focada).
 * Sem prioridade, confere se o programa ficou equilibrado.
 */
class GoalAlignment(private val kb: KnowledgeBase) {
    private val rule = kb.ruleSet.priority

    fun check(program: Program, profile: UserProfile): AlignmentReport {
        val p = rule.params
        val sessions = program.sessions
        val n = sessions.size
        val weekly = VolumeCalculator(kb.ruleSet.volume.params).weekly(sessions)
        val base = kb.ruleSet.volume.params.target(program.focus, program.tier)
        val all = sessions.flatMap { it.exercises }
        fun half(e: PlannedExercise, region: BodyRegion): Boolean {
            val wanted = when (region) { BodyRegion.GLUTES, BodyRegion.LEGS -> "lower"; BodyRegion.CORE -> null; else -> "upper" }
            return wanted == null || e.exercise.primaryMuscles.any { kb.muscle(it).region == wanted }
        }

        val coverage = BodyRegion.values().mapNotNull { region ->
            val muscles = kb.trackedMuscles.filter { it.focusRegion == region }
            if (muscles.isEmpty()) return@mapNotNull null
            val ids = muscles.map { it.id }.toSet()
            val frequency = sessions.count { s -> s.exercises.any { e -> e.sets >= 2 && e.exercise.primaryMuscles.any { it in ids } } }
            val focused = all.filter { e -> e.exercise.focus.any { it in ids } }.sumOf { it.sets }
            val halfSets = all.filter { half(it, region) }.sumOf { it.sets }
            val share = if (halfSets == 0) 0.0 else focused.toDouble() / halfSets
            // Dias em que a região é o foco: há composto com FOCO nela (o terra no dia de glúteos não é dia de costas).
            val withCompound = sessions.filter { s -> s.exercises.any { e -> e.role != SlotRole.ACCESSORY && e.exercise.focus.any { it in ids } } }
            val leading = withCompound.count { s ->
                s.exercises.indexOfFirst { e -> e.exercise.focus.any { it in ids } } in 0 until p.leadingPositions
            }
            val normalTarget = muscles.maxOf { base.target * it.volumeFactor }
            fun focusedOn(m: MuscleId) = all.filter { e -> m in e.exercise.focus }.sumOf { it.sets }
            val minFreq = p.minFrequency(region, n)
            val minShare = p.minShareOfHalf[region] ?: 0.0
            val priority = region in profile.priorities
            val vol = muscles.map { weekly[it.id] ?: 0.0 }.average()
            val below = muscles.filter { m -> (weekly[m.id] ?: 0.0) + 1e-9 < (program.volumeTargets[m.id]?.min ?: 0.0) }
                .map { m -> "${m.name.lowercase()} ${Fmt.num(weekly[m.id] ?: 0.0)} de ${Fmt.num(program.volumeTargets[m.id]?.min ?: 0.0)}" }
            val focusedOk = muscles.all { m -> focusedOn(m.id) + 1e-9 >= base.target * m.volumeFactor * p.minFocusedOfTarget }
            val ok = if (priority) frequency >= minFreq && focusedOk && share + 1e-9 >= minShare &&
                leading == withCompound.size && below.isEmpty()
            else below.isEmpty()
            RegionCoverage(region, priority, frequency, minFreq, focused, normalTarget, focusedOk, below, vol, share, minShare,
                leading, withCompound.size, ok)
        }

        val lines = mutableListOf<Explanation>()
        val warnings = mutableListOf<Explanation>()
        if (profile.priorities.isEmpty()) {
            // Todos os músculos rastreados (inclui panturrilha, que não pertence a nenhuma região priorizável).
            val tracked = kb.trackedMuscles
            val low = tracked.filter { m -> (weekly[m.id] ?: 0.0) + 1e-9 < (program.volumeTargets[m.id]?.min ?: 0.0) }
            lines += Explanation.rule(
                "Treino equilibrado (sem região prioritária): ${tracked.size - low.size} de ${tracked.size} músculos dentro da faixa semanal planejada." +
                    if (low.isEmpty()) "" else " Abaixo do mínimo: ${low.joinToString { it.name.lowercase() }}.",
                rule.id,
            )
        }
        for (c in coverage.filter { it.priority }) {
            val name = c.region.label
            val pct = (c.shareOfHalf * 100).toInt()
            val halfName = when (c.region) {
                BodyRegion.GLUTES, BodyRegion.LEGS -> "das séries de inferiores"
                BodyRegion.CORE -> "das séries da semana"
                else -> "das séries de superiores"
            }
            val freqText = "$name em ${c.frequency} de $n treinos da semana"
            val perMuscle = if (kb.trackedMuscles.count { it.focusRegion == c.region } > 1) " por músculo" else ""
            val focusText = "${c.focusedSets} séries por semana em exercícios com foco em ${name.lowercase()} (referência sem prioridade: ${Fmt.num(c.normalTarget)}$perMuscle)"
            val shareText = "$pct% $halfName são focadas em ${name.lowercase()}"
            val posText = when {
                c.sessionsWithCompound == 0 -> "entra como acessório"
                c.leadingSessions == c.sessionsWithCompound -> "abre o treino nos ${c.sessionsWithCompound} dias em que é o foco"
                else -> "abre o treino em ${c.leadingSessions} de ${c.sessionsWithCompound} dias em que é o foco"
            }
            val text = "$freqText; $focusText; $shareText; $posText."
            if (c.ok) lines += Explanation.rule("✅ $text", rule.id)
            else {
                val why = buildList {
                    if (c.frequency < c.minFrequency) add("frequência abaixo de ${c.minFrequency}× (faltou tempo ou equipamento compatível)")
                    if (!c.focusedOk) add("poucas séries focadas — o tempo ou o equipamento limitou")
                    if (c.musclesBelowMin.isNotEmpty()) add("abaixo do mínimo da prioridade (séries/semana): ${c.musclesBelowMin.joinToString()}")
                    if (c.shareOfHalf + 1e-9 < c.minShare) add("os exercícios escolhidos trabalham mais outras regiões (meta: ${(c.minShare * 100).toInt()}% ou mais)")
                    if (c.leadingSessions < c.sessionsWithCompound) add("em alguns treinos a região não ficou no começo")
                }
                warnings += Explanation.rule("⚠️ $text Atenção: ${why.joinToString("; ")}.", rule.id)
            }
        }
        val others = coverage.filter { !it.priority }
        if (profile.priorities.any { it !in p.noReductionFor } && others.isNotEmpty()) {
            lines += Explanation.rule(
                "Para caber a prioridade no mesmo tempo, as demais regiões têm meta e teto de manutenção — o excesso é cortado " +
                    "(planejado: ${Fmt.num(others.minOf { it.weeklySets })}–${Fmt.num(others.maxOf { it.weeklySets })} séries/semana); " +
                    "músculos que trabalham junto com a prioridade não são reduzidos.",
                rule.id,
            )
        }
        // A promessa é "as demais regiões continuam sendo treinadas": quase nada fora da prioridade vira aviso.
        if (profile.priorities.isNotEmpty()) {
            val priorityMuscles = kb.musclesOf(profile.priorities)
            val starved = kb.trackedMuscles.filter { m ->
                m.id !in priorityMuscles && (weekly[m.id] ?: 0.0) + 1e-9 < (program.volumeTargets[m.id]?.min ?: 0.0) * 0.5
            }
            if (starved.isNotEmpty()) warnings += Explanation.rule(
                "⚠️ Fora da prioridade, ficaram com pouco ou nenhum treino: ${starved.joinToString { "${it.name.lowercase()} ${Fmt.num(weekly[it.id] ?: 0.0)}" }} " +
                    "séries/semana. Com mais tempo por treino (ou um dia a mais) o motor mantém a manutenção do resto do corpo.",
                rule.id,
            )
        }
        // Objetivos de "perder barriga" já recebem o aviso completo do gerador; aqui só quem prioriza abdômen.
        val goalNotice = profile.primaryGoal.spotReductionNotice || profile.secondaryGoal?.spotReductionNotice == true
        if (BodyRegion.CORE in profile.priorities && !goalNotice) {
            kb.claims.firstOrNull { it.id == ClaimId("spot_reduction") }?.let {
                lines += Explanation(Provenance.FACT, "Priorizar abdômen fortalece o músculo, mas não acelera a perda de gordura da barriga.", claimIds = listOf(it.id))
            }
        }
        return AlignmentReport(coverage, lines, warnings, n)
    }
}
