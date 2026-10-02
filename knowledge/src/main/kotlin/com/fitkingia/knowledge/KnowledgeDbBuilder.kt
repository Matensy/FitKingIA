package com.fitkingia.knowledge

import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import com.fitkingia.knowledge.sql.JdbcSqlDatabase
import com.fitkingia.knowledge.sql.SqlScript

/** Gera o fitness.db (SQLite) a partir dos seeds. Falha se qualquer restrição do schema for violada. */
object KnowledgeDbBuilder {

    fun build(seeds: Seeds, target: File): File {
        target.parentFile?.mkdirs()
        if (target.exists()) check(target.delete()) { "Não foi possível sobrescrever $target" }
        JdbcSqlDatabase.connect(target.absolutePath).use { c ->
            c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            runScript(c, schema("knowledge.sql"))
            c.autoCommit = false
            insertAll(c, seeds)
            c.commit()
            c.autoCommit = true
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA foreign_key_check").use { rs ->
                    check(!rs.next()) { "Violação de chave estrangeira em ${rs.getString(1)}" }
                }
                st.execute("VACUUM")
            }
        }
        return target
    }

    fun schema(name: String): String =
        KnowledgeDbBuilder::class.java.getResourceAsStream("/schema/$name")?.bufferedReader()?.readText()
            ?: error("Schema $name não encontrado")

    /** Executa um script SQL com múltiplos comandos (sem triggers com ';' internos). */
    fun runScript(c: Connection, sql: String) {
        c.createStatement().use { st -> SqlScript.statements(sql).forEach { st.execute(it) } }
    }

    private fun insertAll(c: Connection, s: Seeds) {
        fun ins(sql: String, block: (PreparedStatement) -> Unit) = c.prepareStatement(sql).use { ps -> block(ps); ps.executeBatch() }
        fun PreparedStatement.row(vararg v: Any?) { v.forEachIndexed { i, x -> setObject(i + 1, x) }; addBatch() }

        ins("INSERT INTO meta(key, value) VALUES (?, ?)") { ps ->
            ps.row("schema_version", s.meta.schemaVersion)
            ps.row("content_version", s.meta.contentVersion)
            ps.row("last_reviewed", s.meta.lastReviewed)
            ps.row("description", s.meta.description)
            ps.row("built_at", Instant.now().toString())
        }
        ins("INSERT INTO evidence_sources VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)") { ps ->
            s.sources.forEach { ps.row(it.id, it.title, it.authors, it.organization, it.year, it.venue, it.url, it.doi, it.type, it.tier,
                it.topic, it.summary, it.lastVerified, it.verification, it.verificationNote) }
        }
        ins("INSERT INTO claims VALUES (?,?,?,?,?,?)") { ps ->
            s.claims.forEach { ps.row(it.id, it.statement, it.topic, it.evidenceLevel, it.lastReviewed, it.reviewStatus) }
        }
        ins("INSERT INTO claim_sources VALUES (?,?,?,?)") { ps ->
            s.claims.forEach { c -> c.sources.forEach { ps.row(c.id, it.sourceId, it.stance, it.note) } }
        }
        ins("INSERT INTO rules VALUES (?,?,?,?,?,?,?)") { ps ->
            s.rules.forEach { ps.row(it.id, it.category, it.description, it.basis, it.rationale, it.params.toString(), it.lastReviewed) }
        }
        ins("INSERT INTO rule_claims VALUES (?,?)") { ps -> s.rules.forEach { r -> r.claims.forEach { ps.row(r.id, it) } } }

        ins("INSERT INTO movement_patterns VALUES (?,?,?)") { ps -> s.patterns.forEach { ps.row(it.id, it.name, it.description) } }
        ins("INSERT INTO pattern_relations VALUES (?,?)") { ps -> s.patterns.forEach { p -> p.related.forEach { ps.row(p.id, it) } } }
        ins("INSERT INTO muscles VALUES (?,?,?,?,?,?)") { ps ->
            s.muscles.forEach { ps.row(it.id, it.name, it.region, if (it.volumeTracked) 1 else 0, it.volumeFactor, it.fillPattern) }
        }
        ins("INSERT INTO equipment VALUES (?,?,?)") { ps -> s.equipment.forEach { ps.row(it.id, it.name, it.category) } }
        ins("INSERT INTO environments VALUES (?,?)") { ps -> s.environments.forEach { ps.row(it.id, it.name) } }
        ins("INSERT INTO environment_equipment VALUES (?,?)") { ps -> s.environments.forEach { e -> e.equipment.forEach { ps.row(e.id, it) } } }

        ins("INSERT INTO exercises VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)") { ps ->
            s.exercises.forEach { ps.row(it.id, it.name, it.pattern, it.mechanic, it.laterality, it.loadType, it.difficulty, it.minTier,
                it.stability, it.mobility, it.staple, it.maxTier, if (it.timed) 1 else 0) }
        }
        ins("INSERT INTO exercise_aliases VALUES (?,?)") { ps -> s.exercises.forEach { e -> e.aliases.distinct().forEach { ps.row(e.id, it) } } }
        ins("INSERT INTO exercise_muscles VALUES (?,?,?)") { ps ->
            s.exercises.forEach { e ->
                e.primary.forEach { ps.row(e.id, it, "primary") }
                e.secondary.forEach { ps.row(e.id, it, "secondary") }
            }
        }
        ins("INSERT INTO exercise_equipment VALUES (?,?)") { ps -> s.exercises.forEach { e -> e.equipment.forEach { ps.row(e.id, it) } } }
        ins("INSERT INTO exercise_joint_demand VALUES (?,?,?)") { ps -> s.exercises.forEach { e -> e.jointDemand.forEach { (j, d) -> ps.row(e.id, j, d) } } }
        ins("INSERT INTO exercise_texts VALUES (?,?,?,?)") { ps ->
            s.exercises.forEach { e ->
                e.instructions.forEachIndexed { i, t -> ps.row(e.id, "instruction", i, t) }
                e.commonMistakes.forEachIndexed { i, t -> ps.row(e.id, "common_mistake", i, t) }
                e.safetyNotes.forEachIndexed { i, t -> ps.row(e.id, "safety_note", i, t) }
                e.progressionMethods.forEachIndexed { i, t -> ps.row(e.id, "progression_method", i, t) }
            }
        }
        ins("INSERT INTO exercise_substitutions VALUES (?,?,?)") { ps ->
            s.exercises.forEach { e -> e.substitutes.forEachIndexed { i, sub -> ps.row(e.id, sub, i + 1) } }
        }

        ins("INSERT INTO split_templates VALUES (?,?,?,?,?,?)") { ps ->
            s.splits.forEach { ps.row(it.id, it.name, it.daysPerWeek, it.minTier, it.priority, it.rationale) }
        }
        ins("INSERT INTO split_focuses VALUES (?,?)") { ps -> s.splits.forEach { sp -> sp.focuses.forEach { ps.row(sp.id, it) } } }
        ins("INSERT INTO session_templates VALUES (?,?,?,?)") { ps ->
            s.splits.forEach { sp -> sp.sessions.forEachIndexed { i, se -> ps.row(sp.id, i, se.key, se.name) } }
        }
        ins("INSERT INTO session_slots VALUES (?,?,?,?,?,?,?,?,?)") { ps ->
            s.splits.forEach { sp ->
                sp.sessions.forEachIndexed { si, se ->
                    se.slots.forEachIndexed { i, sl ->
                        ps.row(sp.id, si, i, sl.pattern, sl.role, sl.baseSets, sl.targetMuscle, sl.preferMechanic, sl.preferLaterality)
                    }
                }
            }
        }

        ins("INSERT INTO sports VALUES (?,?,?,?,?,?)") { ps ->
            s.sports.forEach { ps.row(it.id, it.name, it.lowerBodyLoad, it.upperBodyLoad, it.cardioDemand, it.recoveryDemand) }
        }
        ins("INSERT INTO safety_questions VALUES (?,?,?,?,?,?,?)") { ps ->
            s.safetyQuestions.forEachIndexed { i, q -> ps.row(q.id, i, q.question, q.category, q.outcomeIfYes, q.message, q.appliesTo) }
        }
        ins("INSERT INTO foods VALUES (?,?,?,?,?,?,?,?,?,?,?,?)") { ps ->
            s.foods.forEach { ps.row(it.id, it.name, it.kcal, it.proteinG, it.carbsG, it.fatG, it.fiberG, it.sodiumMg,
                it.defaultServingG, it.servingLabel, it.sourceId, it.verification) }
        }
        ins("INSERT INTO food_aliases VALUES (?,?)") { ps -> s.foods.forEach { f -> f.aliases.distinct().forEach { ps.row(f.id, it) } } }
        ins("INSERT INTO supplements VALUES (?,?,?,?,?,?,?,?,?,?)") { ps ->
            s.supplements.forEach { ps.row(it.id, it.name, it.whatIs, it.purpose, it.evidenceSummary, it.howStudied,
                it.knownEffects, it.limitations, it.cautions, it.evidenceLevel) }
        }
        ins("INSERT INTO supplement_sources VALUES (?,?)") { ps -> s.supplements.forEach { su -> su.sources.forEach { ps.row(su.id, it) } } }
    }
}
