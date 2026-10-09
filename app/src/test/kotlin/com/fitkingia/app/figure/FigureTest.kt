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
        for (id in listOf("barra_fixa", "barra_fixa_assistida", "barra_fixa_maquina", "elevacao_joelhos")) {
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
        assertEquals("concha", m("Concha com elástico", "hip_abduction", "BAND"))
        assertEquals("flexora_bola", m("Flexora na bola suíça", "knee_flexion", "BODYWEIGHT"))
        assertEquals("abdominal_bola", m("Abdominal na bola suíça", "trunk_flexion", "BODYWEIGHT"))
        assertEquals("agachamento_sumo", m("Agachamento sumô com kettlebell", "squat", "KETTLEBELL"))
    }

    /**
     * Palavra solta no nome não troca o movimento quando o padrão diz outra coisa: o primeiro
     * "supino declinado" que entrar no banco não pode mostrar um abdominal.
     */
    @Test fun nameRulesRespectTheMovementPattern() {
        fun m(name: String, pattern: String, load: String, eq: Set<String> = emptySet()) =
            FigureMapping.motionId("novo", name, emptyList(), pattern, load, eq)
        assertEquals("supino_barra", m("Supino declinado com barra", "horizontal_push", "BARBELL"))
        assertEquals("crucifixo_halteres", m("Crucifixo declinado com halteres", "horizontal_adduction", "DUMBBELL"))
        assertEquals("panturrilha_maquina", m("Panturrilha no hack", "calf_raise", "MACHINE"))
        assertEquals("elevacao_joelhos", m("Elevação de pernas na barra fixa", "trunk_flexion", "BODYWEIGHT"))
        assertEquals("abducao_em_pe", m("Hidrante em quatro apoios", "hip_abduction", "BODYWEIGHT"))
        assertEquals("adutora", m("Adução de quadril deitado de lado", "hip_adduction", "BODYWEIGHT"))
        assertEquals("triceps_frances_halter", m("Tríceps coice com halter", "elbow_extension", "DUMBBELL"))
        // Nos padrões certos, as mesmas palavras continuam valendo.
        assertEquals("abdominal_declinado", m("Abdominal declinado com anilha", "trunk_flexion", "BODYWEIGHT"))
        assertEquals("agachamento_hack", m("Hack invertido", "squat", "MACHINE"))
        assertEquals("barra_fixa", m("Barra fixa neutra", "vertical_pull", "BODYWEIGHT"))
        // Sumô com barra nas costas não é o halter entre as pernas: fica com o agachamento com barra.
        assertEquals("agachamento_barra", m("Agachamento sumô com barra", "squat", "BARBELL"))
        // Padrão novo, que o mapa ainda não conhece: só o nome orienta.
        assertEquals("abdominal_declinado", m("Abdominal declinado", "padrao_novo", "BODYWEIGHT"))
        // Equipamento também diferencia variações de aparelho.
        assertEquals("hip_thrust_maquina", m("Glute drive", "hip_extension", "MACHINE", setOf("hip_thrust_machine")))
        assertEquals("barra_fixa_maquina", m("Graviton", "vertical_pull", "MACHINE", setOf("assisted_pull_up_machine")))
    }

    /**
     * Acessório citado no nome não troca o movimento inteiro: mini band num coice ou numa elevação
     * pélvica com barra não vira ponte no chão; bola medicinal não é bola suíça; elevação de pernas
     * deitado não ganha barra fixa.
     */
    @Test fun accessoryWordsDoNotOverrideTheMovement() {
        fun m(name: String, pattern: String, load: String, eq: Set<String> = emptySet()) =
            FigureMapping.motionId("novo", name, emptyList(), pattern, load, eq)
        val miniBand = setOf("mini_band")
        assertEquals("coice_quatro_apoios", m("Coice em quatro apoios com mini band", "hip_extension", "BAND", miniBand))
        assertEquals("coice_polia", m("Coice em pé com mini elástico", "hip_extension", "BAND", miniBand))
        assertEquals("hip_thrust_barra", m("Elevação pélvica com barra e mini band", "hip_extension", "BARBELL", setOf("barbell", "flat_bench", "mini_band")))
        assertEquals("hip_thrust_halter", m("Elevação pélvica com halter", "hip_extension", "DUMBBELL", setOf("dumbbells", "flat_bench", "mini_band")))
        assertEquals("ponte_mini_band", m("Ponte com mini band", "hip_extension", "BAND", miniBand))
        assertEquals("ponte_mini_band", m("Elevação de quadril no chão", "hip_extension", "BAND", miniBand))
        assertEquals("ponte", m("Elevação de quadril no chão", "hip_extension", "BODYWEIGHT"))
        assertEquals("abdominal", m("Abdominal com bola medicinal", "trunk_flexion", "BODYWEIGHT", setOf("medicine_ball")))
        assertEquals("abdominal_bola", m("Abdominal na bola", "trunk_flexion", "BODYWEIGHT"))
        assertEquals("abdominal", m("Elevação de pernas deitado", "trunk_flexion", "BODYWEIGHT"))
        assertEquals("elevacao_joelhos", m("Elevação de pernas pendurado", "trunk_flexion", "BODYWEIGHT"))
    }

    /** Variações com acessório ou aparelho próprio abrem a figura com esse acessório. */
    @Test fun equipmentVariationsHaveTheirOwnFigures() {
        val expected = mapOf(
            "mini_band_clamshell" to "concha",
            "dumbbell_sumo_squat" to "agachamento_sumo",
            "swiss_ball_leg_curl" to "flexora_bola",
            "swiss_ball_crunch" to "abdominal_bola",
            "machine_glute_kickback" to "coice_maquina",
            "machine_hip_thrust" to "hip_thrust_maquina",
            "smith_hip_thrust" to "hip_thrust_smith",
            "machine_assisted_pull_up" to "barra_fixa_maquina",
            "mini_band_glute_bridge" to "ponte_mini_band",
        )
        for ((exercise, motion) in expected) {
            val ex = kb.exercises.firstOrNull { it.id.value == exercise } ?: continue // exercício saiu do banco
            assertEquals(exercise, motion, FigureMapping.motionFor(ex).id)
        }
        // Sem caneleira (disco laranja) na concha: o acessório é a mini band, entre os joelhos.
        val shell = MotionCatalog.get("concha")
        assertTrue(shell.props.none { it is Prop.Disc })
        assertTrue(shell.props.any { it is Prop.Line && it.ink == Ink.BAND })
    }

    /** Concha: pés juntos o tempo todo, só o joelho de cima abre; o de baixo não sai do lugar. */
    @Test fun clamshellOpensTheTopKneeWithFeetTogether() {
        val m = MotionCatalog.get("concha")
        val closed = m.pose(0f)
        val open = m.pose(1f)
        assertTrue("joelho de cima deveria subir", closed[J.KNEE_B].y - open[J.KNEE_B].y > 8f)
        for (f in samples) {
            val pose = m.pose(f)
            assertTrue("pés separados em f=$f", pose[J.ANKLE].dist(pose[J.ANKLE_B]) < 5f)
            assertTrue("joelho de baixo mexeu em f=$f", pose[J.KNEE].dist(closed[J.KNEE]) < 0.5f)
            // Quadris e joelhos flexionados: o joelho fica fora da reta quadril–tornozelo.
            val hip = pose[J.HIP]; val ankle = pose[J.ANKLE]; val knee = pose[J.KNEE]
            val u = (ankle - hip).unit()
            assertTrue("perna de baixo estendida em f=$f", abs((knee - hip).dot(u.perp())) > 5f)
        }
    }

    /** Sumô: o halter fica pendurado entre as pernas, abaixo das mãos (e não no peito). */
    @Test fun sumoSquatHoldsTheDumbbellBetweenTheLegs() {
        val m = MotionCatalog.get("agachamento_sumo")
        // Vista de frente: os braços descem na frente do tronco; sem contorno sumiriam nele
        // (e o halter pareceria preso ao corpo, sem mãos).
        assertTrue(m.rig.armsOverTorso)
        for (f in samples) {
            val pose = m.pose(f)
            val hands = (pose[J.WRIST] + pose[J.WRIST_B]) * 0.5f
            assertTrue("mãos fora do meio em f=$f", hands.x > pose[J.HIP_B].x && hands.x < pose[J.HIP].x)
            assertTrue("mãos acima da pelve em f=$f", hands.y > pose[J.PELVIS].y)
            assertTrue("pés pouco afastados em f=$f", pose[J.ANKLE].x - pose[J.ANKLE_B].x > 25f)
        }
    }

    /** Barra assistida: a coluna da plataforma fica em pé (vertical) e no chão em todo o movimento. */
    @Test fun assistedPullUpColumnStaysUpright() {
        val m = MotionCatalog.get("barra_fixa_maquina")
        val column = m.props.filterIsInstance<Prop.Line>().single { it.ink == Ink.FRAME && it.b !is Anchor.At }
        for (f in samples) {
            val pose = m.pose(f)
            val top = pose.resolve(column.a)
            val bottom = pose.resolve(column.b)
            assertEquals("coluna inclinada em f=$f", top.x, bottom.x, 0.01f)
            assertEquals("coluna fora do chão em f=$f", GROUND, bottom.y, 0.01f)
        }
    }

    /** Bola suíça: apoiada no chão em todos os quadros (os calcanhares em cima dela). */
    @Test fun swissBallStaysOnTheFloor() {
        for (id in listOf("flexora_bola", "abdominal_bola")) {
            val m = MotionCatalog.get(id)
            val ball = m.props.filterIsInstance<Prop.Disc>().single { it.ink == Ink.PAD }
            for (f in samples) {
                val c = m.pose(f).resolve(ball.at)
                assertEquals("$id: bola fora do chão em f=$f", GROUND, c.y + ball.r, 0.6f)
            }
        }
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

    /**
     * Nenhum segmento muda de comprimento mais de 10% entre quadros (nem na interpolação).
     * Exceção declarada: membros Limb.Free (vistas com escorço, em que o tamanho aparente muda).
     */
    @Test fun segmentLengthsStableAcrossFrames() {
        for (m in MotionCatalog.all) {
            val ref = m.pose(0f)
            val free = HashSet<J>()
            for (k in m.keys) {
                if (k.arm is Limb.Free) free += listOf(J.ELBOW, J.WRIST)
                if (k.armB is Limb.Free) free += listOf(J.ELBOW_B, J.WRIST_B)
                if (k.leg.limb is Limb.Free) free += listOf(J.KNEE, J.ANKLE)
                if (k.legB.limb is Limb.Free) free += listOf(J.KNEE_B, J.ANKLE_B)
            }
            for (f in samples) {
                val pose = m.pose(f)
                for ((a, b) in Pose.SEGMENTS) {
                    if (b in free) continue
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
