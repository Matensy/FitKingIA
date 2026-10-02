# ADR 0002 — Dois bancos SQLite: conhecimento (fitness.db) e usuário (user.db)

**Status:** aceita · 2026-10-02

## Contexto
O conhecimento (exercícios, regras, evidências, alimentos) muda por versão e é igual para todos; os dados pessoais são privados e precisam sobreviver às atualizações de conteúdo.

## Decisão
- `fitness.db`: somente leitura, gerado no build a partir de JSON versionado em git (revisável em pull request), validado por `KnowledgeValidator` e por `CHECK`/FK no schema.
- `user.db`: dados do usuário, local, com consentimentos, `recommendation_log` (regra + versão do conhecimento de cada recomendação) e cascata para exclusão total.
- IDs de exercícios/alimentos ligam os dois bancos por valor (sem FK entre arquivos).

## Consequências
- Atualizar conteúdo = trocar o `fitness.db`; nenhum risco aos dados pessoais.
- Recomendações antigas continuam explicáveis pela versão registrada.
- O JSON é a fonte da verdade; o `.db` é artefato (não versionado).
