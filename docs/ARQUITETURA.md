# Arquitetura

## Visão geral

```
┌──────────────────────────────────────────────────────────────────────┐
│ app (Android, telas em código)          cli `fitking` (terminal)     │
│  questionário só de toque, Hoje,          + coach (IA local de        │
│  Semana, Treino, Progresso, Mais          conversa, só na CLI)        │
└──────────────┬───────────────────────────────────────────────────────┘
               │ casos de uso
        ┌──────▼───────────────────────────────────────────────────────┐
        │ appcore — lógica do app sem Android (testada na JVM)         │
        │  Questionnaire → UserProfile · FitKing (programa, semana,    │
        │  treino perdido, autorregulação, execução, PRs, XP, água…)   │
        │  UserRepository (user.db) · Codec (JSON de sessões)          │
        └──────┬───────────────────────────────────────────────────────┘
               │ chama motores (nunca prescreve por conta própria)
        ┌──────▼───────────────────────────────────────────────────────┐
        │ core — domínio puro (sem IO)                                 │
        │  SafetyScreening → ProgramGenerator → SessionFitter/Adapter │
        │  SubstitutionEngine · ProgressionEngine · VolumePlanner     │
        │  WeekScheduler · Recovery · Analytics · Nutrition · …       │
        │  WhyService (recomendação → regra → evidência → fonte)      │
        └──────▲───────────────────────────────────────▲───────────────┘
               │ KnowledgeBase (somente leitura)       │ perfil, histórico
        ┌──────┴───────────────┐               ┌───────┴──────────────┐
        │ fitness.db (SQLite)  │               │ user.db (SQLite)     │
        │ conhecimento, gerado │               │ dados pessoais, só   │
        │ de JSON, nos assets  │               │ no aparelho          │
        └──────────────────────┘               └──────────────────────┘
        acesso via SqlDatabase: JDBC (JVM) · SQLiteDatabase (Android)
```

## Módulos Gradle

| Módulo | Depende de | Roda em | Responsabilidade |
|---|---|---|---|
| `core` | — | JVM, Android | Modelos e motores determinísticos. Nenhuma dependência externa. |
| `knowledge` | `core`, kotlinx-serialization, sqlite-jdbc (só JVM) | JVM, Android | Seeds JSON → `fitness.db`; `KnowledgeReader` (banco → `KnowledgeBase`) sobre `SqlDatabase`; schema do `user.db`; validação de integridade. |
| `appcore` | `knowledge` | JVM, Android | Questionário, `UserRepository` (user.db), casos de uso do app (`FitKing`). |
| `app` | `appcore`, `android.jar` (compileOnly) | Android 8+ | Telas, `AndroidSqlDatabase`, montagem do APK sem AGP. |
| `coach` | `core`, kotlinx-serialization | JVM | IA local de conversa (CLI). |
| `cli` | `knowledge`, `coach` | JVM | Ferramenta de terminal. |

## App Android

- **Dados**: na primeira abertura de cada versão do APK, `assets/fitness.db` é copiado e aberto só para leitura; o `user.db` é criado com `assets/user.sql` e versionado por `PRAGMA user_version`. Programa ativo em `programs/program_sessions/program_exercises`; replanejamentos de uma semana (treino perdido) em `week_plans`; o treino em andamento guarda a sessão do dia em `workouts.plan_json` e cada série em `workout_sets` assim que é registrada (dá para retomar).
- **Telas**: `MainActivity` com pilha de telas e barra inferior (Hoje, Semana, Progresso, Mais). Cada `Screen` reconstrói sua UI a partir do estado; trabalho pesado (gerar programa, simular) roda fora da thread de UI.
- **Build do APK**: ver [ADR 0004](adr/0004-apk-sem-agp.md) — Kotlin → ProGuard (backport) → dx → aapt2 → zipalign → apksigner, com checagem de API do Android 8.

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

- **`fitness.db`** (conhecimento): gerado a partir de `knowledge/src/main/resources/knowledge/*.json` por `./gradlew :knowledge:buildKnowledgeDb` e empacotado no APK. Somente leitura em runtime. Schema: `knowledge/src/main/resources/schema/knowledge.sql` (com `CHECK`s, chaves estrangeiras e visões de auditoria como `v_rule_evidence` e `v_conflicting_claims`).
- **`user.db`** (pessoal): schema em `knowledge/src/main/resources/schema/user.sql` — perfil, consentimentos, disponibilidade, limitações, medidas, fotos, programas, treinos e séries realizadas, refeições, água, sono, cardio, PRs, `recommendation_log` (que regra e que versão do conhecimento geraram cada recomendação) e conversas com a IA (só com consentimento). `ON DELETE CASCADE` implementa "apagar meus dados".

Ver [ADR 0002](adr/0002-dois-bancos-sqlite.md).

## Testes

| Suíte | O que garante |
|---|---|
| `core` (66) | Motores contra uma base mínima fixa: determinismo, equipamento, nível, teto de volume, tempo, segurança, progressão, PRs, tendências, ferramentas. |
| `knowledge` (234) | Integridade do banco real, cadeia de evidências, visões SQL, `CHECK`s, cascata do `user.db` e **216 cenários de perfil** (6 ambientes × 3 níveis × 4 frequências × 3 objetivos) testados por propriedades. |
| `appcore` (104) | Fluxo do app sobre SQLite em memória: questionário → programa salvo e recarregado idêntico, triagem, dor, semana e treino perdido, treino → progressão → PR, retomar treino, pouco tempo/prontidão, troca, água/corpo/sono/comida/cardio, exportar e apagar dados, simulador; **90 combinações de respostas** do questionário. |
| `app` (6 + capturas) | Robolectric roda a Activity real: questionário só com toques até o treino concluído, todas as abas e telas de Mais, treino perdido A–D, triagem com alerta. O fluxo completo também roda sobre o **SQLite do Android** (`AndroidSqlDatabase`), e o conhecimento lido por ele é comparado com o lido via JDBC. `-Pscreenshots` gera PNGs das telas. |
| `coach` (23) | Diálogos de várias trocas, segurança, cada intenção, e avaliação do entendimento em conjuntos de desenvolvimento e de teste. |
| `cli` (2) | Fumaça de ponta a ponta. |
