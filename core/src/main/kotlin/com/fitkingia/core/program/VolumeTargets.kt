package com.fitkingia.core.program

import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.VolumeTarget
import com.fitkingia.core.model.*

/**
 * Metas semanais por músculo, já com a prioridade por região: a região escolhida sobe, os sinergistas
 * ficam como estão e o resto vai para manutenção (regra `priority.region`). Usada na geração e ao
 * recarregar um programa salvo, para que as metas sejam sempre as mesmas.
 */
class VolumeTargets(private val kb: KnowledgeBase) {
    fun forProgram(focus: TrainingFocus, tier: TrainingTier, priorities: Set<BodyRegion>): Map<MuscleId, VolumeTarget> {
        val base = kb.ruleSet.volume.params.target(focus, tier)
        val p = kb.ruleSet.priority.params
        val priority = kb.trackedMuscles.filter { it.focusRegion != null && it.focusRegion in priorities }.map { it.id }.toSet()
        val synergists = priorities.flatMap { p.synergists[it].orEmpty() }.toSet()
        val reduceOthers = priorities.any { it !in p.noReductionFor }
        return kb.trackedMuscles.associate { m ->
            val t = base.scaled(m.volumeFactor)
            m.id to when {
                m.id in priority -> VolumeTarget(t.target * p.priorityMinOfTarget, t.target * p.priorityTargetFactor, t.max * p.priorityMaxFactor)
                !reduceOthers || m.id in synergists -> t
                else -> VolumeTarget(t.min * p.otherMinFactor, t.target * p.otherTargetFactor, t.max * p.otherMaxFactor)
            }
        }
    }
}
