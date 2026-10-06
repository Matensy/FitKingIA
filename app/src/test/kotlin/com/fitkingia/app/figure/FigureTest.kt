package com.fitkingia.app.figure

import com.fitkingia.knowledge.BundledKnowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Ilustrações: todo exercício tem figura e as poses são fisicamente coerentes (JVM pura). */
class FigureTest {
    private val kb = BundledKnowledge.load()
    private val samples = (0..40).map { it / 40f }

    @Test fun everyExerciseMapsToAnExistingMotion() {
        val missing = kb.exercises.filter { MotionCatalog.find(FigureMapping.motionFor(it).id) == null }
        assertTrue("sem ilustração: ${missing.map { it.id }}", missing.isEmpty())
    }

    @Test fun explicitMapPointsToRealExercisesAndMotions() {
        val ids = kb.exercises.map { it.id.value }.toSet()
        for ((exercise, motion) in FigureMapping.explicit) {
            assertTrue("exercício inexistente no mapa: $exercise", exercise in ids)
            assertNotNull("movimento inexistente: $motion", MotionCatalog.find(motion))
        }
    }

    @Test fun nameRulesPointToExistingMotions() {
        for (id in FigureMapping.ruleTargets) assertNotNull("regra por nome aponta para movimento inexistente: $id", MotionCatalog.find(id))
        assertNotNull(MotionCatalog.find(FigureMapping.GENERIC))
    }

    /** Pendurado na barra: os pés ficam longe do chão em todo o movimento. */
    @Test fun hangingMotionsKeepFeetOffTheFloor() {
        for (id in listOf("barra_fixa", "barra_fixa_assistida", "elevacao_joelhos")) {
            val m = MotionCatalog.get(id)
            for (f in samples) {
                val pose = m.pose(f)
                for (j in listOf(J.TOE, J.TOE_B, J.ANKLE, J.ANKLE_B)) assertTrue("$id: $j encostou no chão em f=$f", pose[j].y < GROUND - 6f)
            }
        }
    }

    @Test fun fallbackCoversEveryPatternAndLoadType() {
        val loads = listOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "SMITH", "KETTLEBELL", "BODYWEIGHT", "BAND")
        for (p in kb.patterns) for (l in loads) {
            val id = FigureMapping.byPattern(p.id.value, l, emptySet())
            assertNotNull("${p.id}/$l → $id", MotionCatalog.find(id))
        }
        assertEquals(FigureMapping.GENERIC, FigureMapping.byPattern("padrao_que_nao_existe", "MACHINE", emptySet()))
    }

    @Test fun newExercisesAreRecognizedByName() {
        fun m(name: String, pattern: String, load: String) = FigureMapping.motionId("novo", name, emptyList(), pattern, load, emptySet())
        assertEquals("coice_quatro_apoios", m("Coice em quatro apoios com caneleira", "hip_extension", "BODYWEIGHT"))
        assertEquals("flexora_mesa", m("Mesa flexora", "knee_flexion", "MACHINE"))
        assertEquals("rosca_scott", m("Rosca Scott com barra W", "elbow_flexion", "BARBELL"))
        assertEquals("abducao_polia", m("Abdução de quadril na polia", "hip_abduction", "CABLE"))
        assertEquals("puxada", m("Puxada aberta na máquina", "vertical_pull", "MACHINE"))
    }

    @Test fun motionsHaveValidKeyframes() {
        val ids = HashSet<String>()
        for (m in MotionCatalog.all) {
            assertTrue("id repetido: ${m.id}", ids.add(m.id))
            assertTrue("${m.id}: menos de 2 quadros", m.keys.size >= 2)
            assertTrue("${m.id}: sem nome", m.name.isNotBlank())
            for (f in samples) {
                val pose = m.pose(f) // lança se os quadros misturam tipos de membro/âncora
                for (j in J.values()) assertTrue("${m.id} $j NaN em $f", !pose[j].x.isNaN() && !pose[j].y.isNaN())
            }
            for (ms in 0L..m.periodMs step 50L) assertTrue(m.phase(ms) in 0f..1f)
        }
    }

    /** Nenhum segmento muda de comprimento mais de 10% entre quadros (nem na interpolação). */
    @Test fun segmentLengthsStableAcrossFrames() {
        for (m in MotionCatalog.all) {
            val ref = m.pose(0f)
            for (f in samples) {
                val pose = m.pose(f)
                for ((a, b) in Pose.SEGMENTS) {
                    val l0 = ref[a].dist(ref[b])
                    val l = pose[a].dist(pose[b])
                    if (l0 < 0.5f) continue
                    assertTrue("${m.id}: $a–$b variou ${(abs(l - l0) / l0 * 100).toInt()}% em f=$f", abs(l - l0) / l0 < 0.10f)
                }
            }
        }
    }

    /** Mãos e pés que "seguram"/"apoiam" alcançam o alvo nos quadros-chave (pose possível). */
    @Test fun reachTargetsAreReachableAtKeyframes() {
        val problems = ArrayList<String>()
        for (m in MotionCatalog.all) for ((i, k) in m.keys.withIndex()) {
            val pose = solve(m.rig, k)
            fun check(spec: Limb, end: J) {
                if (spec !is Limb.Reach) return
                val d = pose[end].dist(pose.resolve(spec.target))
                if (d >= 2.5f) problems += "${m.id} quadro $i: $end a ${"%.1f".format(d)} do alvo"
            }
            check(k.arm, J.WRIST); check(k.armB, J.WRIST_B)
            check(k.leg.limb, J.ANKLE); check(k.legB.limb, J.ANKLE_B)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Pés, mãos e cabeça não atravessam o chão (com folga para a espessura do traço). */
    @Test fun bodyStaysAboveTheFloor() {
        val problems = LinkedHashSet<String>()
        val legJoints = setOf(J.TOE, J.TOE_B, J.ANKLE, J.ANKLE_B, J.KNEE, J.KNEE_B)
        for (m in MotionCatalog.all) {
            if (m.rig.view == ViewKind.TOP) continue
            for (f in samples) {
                val pose = m.pose(f)
                for (j in legJoints + listOf(J.WRIST, J.WRIST_B, J.ELBOW, J.ELBOW_B, J.PELVIS)) {
                    if (!m.rig.showLegs && j in legJoints) continue
                    if (pose[j].y >= GROUND + 1.5f) problems += "${m.id}: $j abaixo do chão"
                }
                if (pose[J.HEAD].y + m.rig.headR >= GROUND + 1.5f) problems += "${m.id}: cabeça abaixo do chão"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
