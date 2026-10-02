package com.fitkingia.core.knowledge

import com.fitkingia.core.model.*
import java.time.LocalDate

enum class SourceType(val label: String) {
    GUIDELINE("Diretriz"),
    POSITION_STAND("Position stand"),
    CONSENSUS("Consenso"),
    SYSTEMATIC_REVIEW("Revisão sistemática"),
    META_ANALYSIS("Meta-análise"),
    RCT("Ensaio clínico randomizado"),
    VALIDATION_STUDY("Estudo de validação"),
    NARRATIVE_REVIEW("Revisão narrativa"),
    ARTICLE("Artigo"),
    DATABASE("Base de dados"),
}

enum class VerificationStatus { VERIFIED, PENDING }

data class EvidenceSource(
    val id: SourceId,
    val title: String,
    val authors: String,
    val organization: String?,
    /** Nulo para páginas sem data fixa (ex.: fact sheets atualizados periodicamente). */
    val year: Int?,
    val venue: String?,
    val url: String?,
    val doi: String?,
    val type: SourceType,
    val tier: SourceTier,
    val topic: String,
    val summary: String,
    val lastVerified: LocalDate?,
    val verification: VerificationStatus,
    val verificationNote: String,
) {
    fun citation(): String = buildString {
        append(authors)
        append(" (").append(year?.toString() ?: "s.d.").append("). ")
        append(title)
        venue?.let { append(". ").append(it) }
        doi?.let { append(". doi:").append(it) }
    }
}

enum class Stance { SUPPORTS, CONTRADICTS, CONTEXT }

data class ClaimSource(val sourceId: SourceId, val stance: Stance, val note: String? = null)

enum class ReviewStatus { CURRENT, NEEDS_REVIEW }

/** 🔵 FATO: afirmação rastreável a fontes. Conflito é detectado, nunca "resolvido" artificialmente. */
data class Claim(
    val id: ClaimId,
    val statement: String,
    val topic: String,
    val evidenceLevel: EvidenceLevel,
    val sources: List<ClaimSource>,
    val lastReviewed: LocalDate,
    val reviewStatus: ReviewStatus,
) {
    val isConflicting: Boolean
        get() = sources.any { it.stance == Stance.SUPPORTS } && sources.any { it.stance == Stance.CONTRADICTS }
}

enum class RuleBasis(val label: String) {
    /** Parametrizada a partir de evidências (claims vinculadas). */
    EVIDENCE("Baseada em evidência"),
    /** Heurística do sistema: razoável, documentada, mas sem validação direta. */
    HEURISTIC("Heurística do sistema"),
    /** Convenção prática amplamente usada (ex.: incrementos de anilha). */
    CONVENTION("Convenção prática"),
}

/** 🟢 REGRA DO SISTEMA: metadados da regra; os parâmetros tipados ficam em [RuleSet]. */
data class RuleMeta(
    val id: RuleId,
    val category: String,
    val description: String,
    val basis: RuleBasis,
    val rationale: String,
    val claimIds: List<ClaimId>,
    val lastReviewed: LocalDate,
)
