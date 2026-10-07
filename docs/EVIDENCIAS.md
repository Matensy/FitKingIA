# Política de evidências

Toda recomendação do FitKingIA precisa responder **"por que?"** com uma cadeia auditável:

```
recomendação (o que o app mostrou)
   ↓  RuleId
regra (rules)          → parâmetros + base (EVIDENCE | HEURISTIC | CONVENTION) + justificativa
   ↓  rule_claims
afirmação (claims)     → 🔵 FATO com nível de evidência e data de revisão
   ↓  claim_sources (SUPPORTS | CONTRADICTS | CONTEXT)
fonte (evidence_sources) → autores, ano, periódico, DOI/URL, tipo, tier, verificação
```

No banco: `SELECT * FROM v_rule_evidence WHERE rule_id = 'volume.weekly_sets'`.

## Hierarquia de fontes

| Tier | O que entra | Exemplos no banco |
|---|---|---|
| **S** | Organizações oficiais e profissionais reconhecidas, diretrizes, position stands, consensos | OMS 2020, Ministério da Saúde (Guia de Atividade Física 2021, Guia Alimentar 2014), ACSM 2026 e 2009, ISSN, EFSA, AASM/SRS, NICE |
| **A** | Artigos revisados por pares: revisões sistemáticas, meta-análises, ensaios clínicos, estudos de validação | Schoenfeld 2016/2017, Pelland 2025, Refalo 2023, Grgic 2022, Singer 2024, Coleman 2024, Morton 2018 |
| **B** | Universidades, hospitais, instituições acadêmicas | TACO (NEPA-UNICAMP) |
| ✗ | Não usar como base | redes sociais, influenciadores, blogs comerciais, sites de suplemento |

## Verificação

- Cada fonte tem `verification` (`VERIFIED`/`PENDING`), `last_verified` e uma nota de como foi verificada. O schema impede `VERIFIED` sem data.
- As 35 fontes atuais tiveram título, autores, periódico e DOI conferidos por busca em 2026-10-02. O acesso direto a DOI/PubMed estava bloqueado no ambiente de construção — a nota registra isso.
- **Alimentos**: valores por 100 g conferidos em fontes secundárias que reproduzem a TACO; a conferência no PDF original está pendente e registrada em cada item. Nutriente sem valor confirmado fica `NULL` e o total aparece como "parcial".
- `v_sources_to_verify` lista fontes pendentes ou verificadas há mais de 1 ano.

## Nível de evidência das afirmações

`Alta`, `Moderada`, `Limitada`, `Inconclusiva` — sem "score científico" inventado. O nível é atribuído pela equipe a partir do tipo e da consistência das fontes, e explicado no texto da afirmação.

## Evidência conflitante

Uma afirmação com fontes `SUPPORTS` **e** `CONTRADICTS` é marcada como conflitante (`v_conflicting_claims`). O app mostra os estudos, nunca inventa consenso. Hoje:

- **Perda localizada** — meta-análise (Ramirez-Campillo 2022) e ECR (Vispute 2011) não encontram efeito; um ECR de 2023 (Brobakken) relatou redução localizada.
- **Frequência para hipertrofia** — Schoenfeld 2016 favorece ≥2×/semana; Pelland 2025 encontrou efeito consistente de frequência só para força.

## Regras sem evidência direta

Nem tudo tem estudo direto (ex.: pesos do índice de recuperação, custo do agendamento). Essas regras são marcadas como `HEURISTIC` ou `CONVENTION`, com justificativa obrigatória, e o app as apresenta como heurística do sistema — não como fato.

## Como adicionar ou atualizar conhecimento

1. Edite os JSON em `knowledge/src/main/resources/knowledge/` (fonte, afirmação, regra, exercício…).
2. `./gradlew :knowledge:buildKnowledgeDb` — gera o banco e imprime o relatório de integridade (erros quebram o build).
3. `./gradlew build` — os 216 cenários de perfil verificam que nada quebrou.
4. Atualize `content_version` em `meta.json`. O `user.db` guarda a versão usada em cada recomendação (`recommendation_log`).

## Evidence Update Engine (próxima fase)

A tabela `evidence_updates` já existe para a fila de revisão: novas publicações entram como `PENDING`, ligadas à afirmação afetada; **nenhuma regra muda automaticamente** — a atualização só entra após validação humana.
