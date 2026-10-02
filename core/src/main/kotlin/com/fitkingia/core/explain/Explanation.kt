package com.fitkingia.core.explain

import com.fitkingia.core.model.ClaimId
import com.fitkingia.core.model.RuleId

/**
 * Separação central do projeto (docs/ARQUITETURA.md):
 *  🔵 FATO           — afirmação rastreável a fontes ("A ACSM publicou X").
 *  🟢 REGRA          — decisão determinística do motor ("Para este perfil, o motor selecionou X").
 *  🟣 SUGESTÃO DA IA — texto gerado pela camada de linguagem; nunca decide prescrição.
 */
enum class Provenance(val icon: String, val label: String) {
    FACT("🔵", "Fato"),
    SYSTEM_RULE("🟢", "Regra do sistema"),
    AI_SUGGESTION("🟣", "Sugestão da IA"),
}

data class Explanation(
    val provenance: Provenance,
    val text: String,
    val ruleId: RuleId? = null,
    val claimIds: List<ClaimId> = emptyList(),
) {
    override fun toString() = "${provenance.icon} $text"

    companion object {
        fun rule(text: String, ruleId: RuleId? = null) = Explanation(Provenance.SYSTEM_RULE, text, ruleId)
        fun fact(text: String, claims: List<ClaimId>) = Explanation(Provenance.FACT, text, claimIds = claims)
    }
}
