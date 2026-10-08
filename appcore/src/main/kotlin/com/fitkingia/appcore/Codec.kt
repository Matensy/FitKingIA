package com.fitkingia.appcore

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.explain.Provenance
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.RepPrescription
import com.fitkingia.core.model.ClaimId
import com.fitkingia.core.model.ExerciseId
import com.fitkingia.core.model.RuleId
import com.fitkingia.core.model.SlotRole
import com.fitkingia.core.program.PlannedExercise
import com.fitkingia.core.program.PlannedSession
import kotlinx.serialization.json.*
import java.time.DayOfWeek

/** JSON das estruturas que o user.db guarda como texto (planos da semana, sessão em execução, explicações). */
object Codec {

    fun explanations(list: List<Explanation>): JsonArray = buildJsonArray {
        list.forEach { e ->
            addJsonObject {
                put("p", e.provenance.name)
                put("text", e.text)
                e.ruleId?.let { put("rule", it.value) }
                if (e.claimIds.isNotEmpty()) putJsonArray("claims") { e.claimIds.forEach { add(it.value) } }
            }
        }
    }

    fun explanationsFrom(arr: JsonArray?): List<Explanation> = arr.orEmpty().map { el ->
        val o = el.jsonObject
        Explanation(
            Provenance.valueOf(o.str("p")), LegacyNames.pt(o.str("text")), o["rule"]?.jsonPrimitive?.content?.let(::RuleId),
            o["claims"]?.jsonArray.orEmpty().map { ClaimId(it.jsonPrimitive.content) },
        )
    }

    fun exercise(e: PlannedExercise): JsonObject = buildJsonObject {
        put("id", e.exercise.id.value)
        put("role", e.role.name)
        put("sets", e.sets)
        put("reps", range(e.prescription.reps))
        put("rir", e.prescription.rir)
        put("restRange", range(e.prescription.restSeconds))
        put("tempo", e.prescription.tempo)
        e.prescription.holdSeconds?.let { put("hold", range(it)) }
        put("rest", e.restSeconds)
        e.note?.let { put("note", it) }
    }

    /** Exercícios removidos do banco de conhecimento (atualização de conteúdo) são descartados. */
    fun exerciseFrom(o: JsonObject, kb: KnowledgeBase): PlannedExercise? {
        val ex = kb.exerciseOrNull(ExerciseId(o.str("id"))) ?: return null
        val p = RepPrescription(
            reps = rangeFrom(o["reps"]!!), rir = o.int("rir"), restSeconds = rangeFrom(o["restRange"]!!),
            tempo = o.str("tempo"), holdSeconds = o["hold"]?.let(::rangeFrom),
        )
        return PlannedExercise(ex, SlotRole.valueOf(o.str("role")), o.int("sets"), p, o.int("rest"), note = o["note"]?.jsonPrimitive?.contentOrNull)
    }

    fun session(s: PlannedSession): JsonObject = buildJsonObject {
        put("key", s.key)
        put("name", s.name)
        s.day?.let { put("day", it.value) }
        s.budgetMinutes?.let { put("budget", it) }
        put("est", s.estimatedMinutes)
        putJsonArray("exercises") { s.exercises.forEach { add(exercise(it)) } }
    }

    fun sessionFrom(o: JsonObject, kb: KnowledgeBase): PlannedSession = PlannedSession(
        key = o.str("key"), name = LegacyNames.pt(o.str("name")),
        exercises = o["exercises"]!!.jsonArray.mapNotNull { exerciseFrom(it.jsonObject, kb) },
        day = o["day"]?.jsonPrimitive?.int?.let(DayOfWeek::of),
        budgetMinutes = o["budget"]?.jsonPrimitive?.intOrNull,
        estimatedMinutes = o.int("est"),
    )

    fun sessions(list: List<PlannedSession>): String = JsonArray(list.map(::session)).toString()

    fun sessionsFrom(text: String, kb: KnowledgeBase): List<PlannedSession> =
        Json.parseToJsonElement(text).jsonArray.map { sessionFrom(it.jsonObject, kb) }

    private fun range(r: IntRange) = buildJsonArray { add(r.first); add(r.last) }
    private fun rangeFrom(e: JsonElement) = e.jsonArray.let { it[0].jsonPrimitive.int..it[1].jsonPrimitive.int }
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.int(k: String) = getValue(k).jsonPrimitive.int
}
