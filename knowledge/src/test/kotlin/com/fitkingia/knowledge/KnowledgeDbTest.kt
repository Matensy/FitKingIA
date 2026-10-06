package com.fitkingia.knowledge

import com.fitkingia.core.knowledge.RuleBasis
import com.fitkingia.core.knowledge.Stance
import com.fitkingia.core.knowledge.VerificationStatus
import com.fitkingia.core.model.BodyRegion
import com.fitkingia.core.model.ClaimId
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class KnowledgeDbTest {
    private val kb = BundledKnowledge.load()

    @Test fun `banco gerado sem erros de integridade`() {
        val errors = KnowledgeValidator.validate(kb).filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test fun `conteúdo mínimo esperado`() {
        assertTrue(kb.exercises.size >= 100)
        assertTrue(kb.sources.size >= 30)
        assertTrue(kb.splits.size >= 10)
        assertEquals((1..6).toSet(), kb.splits.filter { it.emphasis.isEmpty() }.map { it.daysPerWeek }.toSet())
        // Modelos com ênfase em glúteos/pernas para 3, 4 e 5 dias (inferiores 3×/semana).
        assertEquals(setOf(3, 4, 5), kb.splits.filter { BodyRegion.GLUTES in it.emphasis }.map { it.daysPerWeek }.toSet())
    }

    @Test fun `toda regra baseada em evidência chega até uma fonte verificada`() {
        for (r in kb.rules.filter { it.basis == RuleBasis.EVIDENCE }) {
            val chain = kb.evidenceChain(r.id)
            val sources = chain.claims.flatMap { (_, s) -> s.filter { it.second.stance == Stance.SUPPORTS }.map { it.first } }
            assertTrue(sources.any { it.verification == VerificationStatus.VERIFIED && it.lastVerified != null }, "${r.id} sem fonte verificada")
        }
    }

    @Test fun `evidência conflitante é detectada e não vira consenso`() {
        val conflicting = kb.claims.filter { it.isConflicting }.map { it.id }.toSet()
        assertTrue(ClaimId("spot_reduction") in conflicting)
        assertTrue(ClaimId("frequency_hypertrophy") in conflicting)
    }

    @Test fun `visões SQL de auditoria funcionam`() {
        val f = Files.createTempFile("fitness", ".db").toFile().also { it.deleteOnExit() }
        KnowledgeDbBuilder.build(Seeds.fromClasspath(), f)
        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM v_conflicting_claims").use { rs -> rs.next(); assertEquals(2, rs.getInt(1)) }
                st.executeQuery("SELECT COUNT(DISTINCT source_id) FROM v_rule_evidence WHERE rule_id = 'volume.weekly_sets'").use { rs ->
                    rs.next(); assertTrue(rs.getInt(1) >= 3)
                }
                st.executeQuery("PRAGMA integrity_check").use { rs -> rs.next(); assertEquals("ok", rs.getString(1)) }
            }
        }
    }

    @Test fun `schema rejeita dados inválidos`() {
        val f = Files.createTempFile("fitness", ".db").toFile().also { it.deleteOnExit() }
        KnowledgeDbBuilder.build(Seeds.fromClasspath(), f)
        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
            c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            assertFails { c.createStatement().use { it.execute("INSERT INTO exercise_muscles VALUES ('barbell_back_squat', 'musculo_inexistente', 'primary')") } }
            assertFails { c.createStatement().use { it.execute("UPDATE exercises SET difficulty = 9 WHERE id = 'leg_press'") } }
            assertFails { c.createStatement().use { it.execute("UPDATE evidence_sources SET last_verified = NULL WHERE id = 'acsm_2026_rt'") } }
        }
    }

    @Test fun `schema do usuário aplica e apaga em cascata`() {
        val f = Files.createTempFile("user", ".db").toFile().also { it.deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
            c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            KnowledgeDbBuilder.runScript(c, KnowledgeDbBuilder.schema("user.sql"))
            c.createStatement().use { st ->
                st.execute("INSERT INTO users(id, name, sex, height_cm, experience) VALUES (1, 'Teste', 'MALE', 178, 'NONE')")
                st.execute("INSERT INTO workouts(id, user_id, started_at) VALUES (1, 1, '2026-10-02T18:00')")
                st.execute("INSERT INTO workout_sets(workout_id, exercise_id, set_index, load_kg, reps, rir) VALUES (1, 'barbell_bench_press', 1, 60, 10, 2)")
                st.execute("INSERT INTO consents(user_id, kind, granted) VALUES (1, 'ai_coach', 0)")
                assertFails { st.execute("INSERT INTO workout_sets(workout_id, exercise_id, set_index, load_kg, reps, rir) VALUES (1, 'x', 2, 60, 10, 11)") }
                // "Apagar dados": remover o usuário remove tudo que é dele.
                st.execute("DELETE FROM users WHERE id = 1")
                st.executeQuery("SELECT (SELECT COUNT(*) FROM workouts) + (SELECT COUNT(*) FROM workout_sets) + (SELECT COUNT(*) FROM consents)").use { rs ->
                    rs.next(); assertEquals(0, rs.getInt(1))
                }
            }
        }
    }

    @Test fun `seeds do diretório e do classpath são os mesmos`() {
        val dir = File("src/main/resources/knowledge")
        if (dir.exists()) assertEquals(Seeds.fromClasspath().exercises, Seeds.fromDirectory(dir).exercises)
    }
}
