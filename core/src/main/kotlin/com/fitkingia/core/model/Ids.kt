package com.fitkingia.core.model

// Identificadores tipados. Todos são slugs estáveis vindos do banco de conhecimento
// (ex.: "back_squat", "quads"), o que mantém o motor genérico: novos músculos,
// padrões ou exercícios entram pelo banco, sem mudança de código.

@JvmInline value class ExerciseId(val value: String) { override fun toString() = value }
@JvmInline value class MuscleId(val value: String) { override fun toString() = value }
@JvmInline value class PatternId(val value: String) { override fun toString() = value }
@JvmInline value class EquipmentId(val value: String) { override fun toString() = value }
@JvmInline value class EnvironmentId(val value: String) { override fun toString() = value }
@JvmInline value class SplitId(val value: String) { override fun toString() = value }
@JvmInline value class SourceId(val value: String) { override fun toString() = value }
@JvmInline value class ClaimId(val value: String) { override fun toString() = value }
@JvmInline value class RuleId(val value: String) { override fun toString() = value }
@JvmInline value class SportId(val value: String) { override fun toString() = value }
@JvmInline value class FoodId(val value: String) { override fun toString() = value }
@JvmInline value class SupplementId(val value: String) { override fun toString() = value }
