# Arquitetura

## Visão geral

```
┌──────────────────────────────────────────────────────────────────────┐
│ APP (Android: Kotlin + Compose — próxima fase) / CLI `fitking` (hoje)│
└──────────────┬─────────────────────────────────────┬─────────────────┘
               │ mensagens do usuário                │ telas / ações
        ┌──────▼───────┐                             │
        │ coach        │  IA local: NLU pt-BR,       │
        │ (LocalCoach) │  diálogo, segurança,        │
        └──────┬───────┘  busca de evidências        │
               │ chama motores (nunca prescreve)     │
        ┌──────▼─────────────────────────────────────▼─────────────────┐
        │ core — domínio puro (sem IO)                                 │
        │  SafetyScreening → ProgramGenerator → SessionFitter/Adapter │
        │  SubstitutionEngine · ProgressionEngine · VolumePlanner     │
        │  WeekScheduler · Recovery · Analytics · Nutrition · …       │
        │  WhyService (recomendação → regra → evidência → fonte)      │
        └──────▲───────────────────────────────────────▲───────────────┘
               │ KnowledgeBase (somente leitura)       │ histórico, perfil
        ┌──────┴───────────────┐               ┌───────┴──────────────┐
        │ fitness.db (SQLite)  │               │ user.db (SQLite)     │
        │ conhecimento         │               │ dados pessoais       │
        │ gerado de JSON (git) │               │ local, com consentim.│
        └──────────────────────┘               └──────────────────────┘
```

## Módulos Gradle

| Módulo | Depende de | Roda em | Responsabilidade |
|---|---|---|---|
| `core` | — | JVM, Android | Modelos e motores determinísticos. Nenhuma dependência externa. |
| `knowledge` | `core`, kotlinx-serialization, sqlite-jdbc | JVM (build e testes) | Seeds JSON → `fitness.db`; carrega o banco para `KnowledgeBase`; validação de integridade. |
| `coach` | `core`, kotlinx-serialization | JVM, Android | IA local. |
| `cli` | `knowledge`, `coach` | JVM | Ferramenta de terminal. |

No Android, o app usará `core` + `coach` diretamente e um adaptador Room para ler o `fitness.db` empacotado em `assets/` (o `SqliteKnowledgeRepository` atual é o adaptador JVM equivalente).

## Fluxo de geração do programa (`ProgramGenerator`)

Pipeline determinístico — mesma entrada, mesmo programa:

1. **Triagem** (`SafetyScreening`): red flag → `ProgramResult.Refused`; dor/condição → `CAUTION` com restrições.
2. **Dias**: dias com tempo ≥ mínimo, limitados por nível; se sobram dias, escolhe a combinação que evita dias consecutivos.
3. **Divisão**: template do banco por nº de dias, nível e foco (`split_templates`); preferência do usuário respeitada quando compatível.
4. **Exercícios por slot** (`ExerciseSelector`): elegibilidade (equipamento, nível, dor relatada, exclusões) e pontuação explicável (papel do slot, curadoria `staple`, dificuldade por nível, variedade na semana, favoritos, histórico).
5. **Distribuição na semana** (`WeekScheduler`): busca exaustiva minimizando sobreposição muscular em dias seguidos, conflito com esportes (ex.: perna pesada colada ao kickboxing) e sessões maiores que o tempo do dia. Conflitos inevitáveis viram aviso.
6. **Tempo do dia** (`SessionFitter`): corta primeiro o que é redundante (músculo já trabalhado *diretamente*), depois descanso, séries de acessórios, secundários… Cada corte tem motivo.
7. **Volume semanal** (`VolumePlanner`): aproxima cada músculo da meta (contagem fracionada: direta 1,0, indireta 0,5), respeitando tempo e teto; completa músculos sem trabalho direto; remove excesso.
8. **Explicações e avisos**: cada etapa emite `Explanation` com proveniência e `RuleId`.

## Regras no banco, não no código

Os parâmetros (metas de volume, faixas de repetição, RIR, descanso, incrementos de carga, pesos do Recovery Score, etc.) estão na tabela `rules` (`params_json`) e são convertidos para tipos em `RuleSet` por `RuleSetParser`. Cada regra declara sua **base** (`EVIDENCE`, `HEURISTIC`, `CONVENTION`), justificativa e afirmações vinculadas. Mudar uma regra = editar o JSON, rodar os testes e o validador.

## Os dois bancos

- **`fitness.db`** (conhecimento): gerado a partir de `knowledge/src/main/resources/knowledge/*.json` por `./gradlew :knowledge:buildKnowledgeDb`. Somente leitura em runtime. Schema: `knowledge/src/main/resources/schema/knowledge.sql` (com `CHECK`s, chaves estrangeiras e visões de auditoria como `v_rule_evidence` e `v_conflicting_claims`).
- **`user.db`** (pessoal): schema em `knowledge/src/main/resources/schema/user.sql` — perfil, consentimentos, disponibilidade, limitações, medidas, fotos, programas, treinos e séries realizadas, refeições, água, sono, cardio, PRs, `recommendation_log` (que regra e que versão do conhecimento geraram cada recomendação) e conversas com a IA (só com consentimento). `ON DELETE CASCADE` implementa "apagar meus dados".

Ver [ADR 0002](adr/0002-dois-bancos-sqlite.md).

## Testes

| Suíte | O que garante |
|---|---|
| `core` (66) | Motores contra uma base mínima fixa: determinismo, equipamento, nível, teto de volume, tempo, segurança, progressão, PRs, tendências, ferramentas. |
| `knowledge` (234) | Integridade do banco real, cadeia de evidências, visões SQL, `CHECK`s, cascata do `user.db` e **216 cenários de perfil** (6 ambientes × 3 níveis × 4 frequências × 3 objetivos) testados por propriedades. |
| `coach` (23) | Diálogos de várias trocas, segurança, cada intenção, e avaliação do entendimento em conjuntos de desenvolvimento e de teste. |
| `cli` (2) | Fumaça de ponta a ponta. |
