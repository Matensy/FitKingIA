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

- **Dados**: na primeira abertura de cada versão do APK, `assets/fitness.db` é copiado e aberto só para leitura; o `user.db` é criado com `assets/user.sql` e versionado por `PRAGMA user_version` (`UserDb.migrate`: banco novo recebe o esquema atual; banco antigo recebe só os passos que faltam — v2 acrescenta `programs.priorities` —, tudo numa transação, inclusive o `user_version`). Programa ativo em `programs/program_sessions/program_exercises`; replanejamentos de uma semana (treino perdido) em `week_plans`; o treino em andamento guarda a sessão do dia em `workouts.plan_json` e cada série em `workout_sets` assim que é registrada (dá para retomar).
- **Telas**: `MainActivity` com pilha de telas e barra inferior (Hoje, Semana, Progresso, Mais). Cada `Screen` reconstrói sua UI a partir do estado; trabalho pesado (gerar programa, simular) roda fora da thread de UI.
- **Movimento** (`app/ui/Motion.kt`): só a navegação anima a tela inteira (empilhar entra da direita, voltar da esquerda, troca de aba com fade cruzado, página do questionário na direção certa, blocos em cascata); um `refresh()` comum só dá retorno no que foi tocado (mola, "pop" do chip selecionado, número do stepper, barra que mudou). Tudo em 150–350 ms, desligado pela preferência do sistema "remover animações" e pelo interruptor `Motion.enabled` (os testes de UI desligam; `MotionTest` liga e confere os estados inicial e final).
- **Ilustrações** (`app/figure`): figuras 2D próprias (poses com quadros-chave e acessórios) desenhadas em `ExerciseFigureView`; `FigureMapping` liga cada exercício a um movimento (mapa explícito por id + regras por nome e padrão para exercícios futuros). Exercício novo no banco → conferir o mapa e as folhas de contato (`-Pscreenshots`, `figuras_*.png`).
- **Lembretes** (`app/notify` + `appcore/Reminders.kt`): `AlarmManager` sempre inexato (`setAndAllowWhileIdle`); com o horário longe, o alarme é uma passagem que só reagenda mais perto (`ReminderPlanner.alarmAt`: o atraso do inexato cresce com a antecedência, 75% dela no Android 8–11), então o aviso chega no máximo uns 15 min depois → receiver carrega o app, `ReminderPlanner` decide se faz sentido avisar naquela hora (água abaixo do ritmo, treino não feito, janela pelo horário marcado com tolerância de 20 min para o alarme atrasado) e reagenda; reagenda também após reiniciar, atualizar ou mudar a hora. Notificação que perdeu o motivo (treino feito ou trocado para outro dia, água em dia, sequência garantida, outro dia, dados apagados) sai da barra (`ReminderPlanner.stillRelevant`, ao sair/voltar ao app e a cada disparo) e todas somem à meia-noite. Categoria "Lembretes" desligada no Android é detectada pelo canal. Canal e builder do Android 8+ por reflexão (compila na API 23).
- **Semana** (`appcore/WeekReorder.kt`): trocar dias e fazer hoje o treino de outro dia gravam um replanejamento da semana (`week_plans`) ou alteram o programa base (troca permanente); cada sessão é reajustada ao tempo do novo dia (protegendo a região priorizada, como o gerador). As opções de treino perdido gravam pelo mesmo caminho, sem apagar trocas anteriores (e, se o treino de hoje já foi feito, valem de amanhã em diante); nada muda a semana enquanto houver treino em andamento no dia (ou com a sessão) afetado; trocar dias e "Fazer hoje" devolvem um "Desfazer" (as opções A–D não têm); "Começar treino" só retoma em silêncio o mesmo treino aberto nesta semana — outro (ou de outra semana) pede antes para retomar ou descartar. Semanas que passaram são só consulta: a troca permanente mantém o que elas mostravam.
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
   Com **prioridade por região** (regra `priority.region`, metas em `VolumeTargets`): a região escolhida tem meta e teto maiores; o resto tem meta e teto de manutenção (o excesso é cortado antes de somar, para liberar tempo); os déficits da prioridade vêm primeiro, inclusive de séries *focadas* (curadoria `focus` do exercício: a elevação pélvica foca glúteos, o agachamento foca quadríceps); sem tempo livre, séries passam no mesmo dia de músculos já na meta para a prioridade. Depois, `ensureFrequency` leva a região a mais sessões (inferiores 3× com 3+ dias) e a ordem coloca os exercícios com foco na região no começo, sem acessório antes de composto. O `SessionFitter` recebe os músculos priorizados e não corta esses exercícios primeiro.
8. **Explicações e avisos**: cada etapa emite `Explanation` com proveniência e `RuleId`.
9. **Objetivo × treino** (`GoalAlignment`): confere o programa gerado (frequência, séries focadas por músculo, nenhum músculo da região abaixo do mínimo, fatia das séries da metade do corpo, ordem) e guarda o resultado em `Program.goalCheck`; o app recalcula ao vivo depois de trocas.

## Regras no banco, não no código

Os parâmetros (metas de volume, faixas de repetição, RIR, descanso, incrementos de carga, pesos do índice de recuperação, etc.) estão na tabela `rules` (`params_json`) e são convertidos para tipos em `RuleSet` por `RuleSetParser`. Cada regra declara sua **base** (`EVIDENCE`, `HEURISTIC`, `CONVENTION`), justificativa e afirmações vinculadas. Mudar uma regra = editar o JSON, rodar os testes e o validador.

## Os dois bancos

- **`fitness.db`** (conhecimento): gerado a partir de `knowledge/src/main/resources/knowledge/*.json` por `./gradlew :knowledge:buildKnowledgeDb` e empacotado no APK. Somente leitura em runtime. Schema: `knowledge/src/main/resources/schema/knowledge.sql` (com `CHECK`s, chaves estrangeiras e visões de auditoria como `v_rule_evidence` e `v_conflicting_claims`).
- **`user.db`** (pessoal): schema em `knowledge/src/main/resources/schema/user.sql` — perfil, consentimentos, disponibilidade, limitações, medidas, fotos, programas, treinos e séries realizadas, refeições, água, sono, cardio, PRs, `recommendation_log` (que regra e que versão do conhecimento geraram cada recomendação) e conversas com a IA (só com consentimento). `ON DELETE CASCADE` implementa "apagar meus dados".

Ver [ADR 0002](adr/0002-dois-bancos-sqlite.md).

## Testes

| Suíte | O que garante |
|---|---|
| `core` (66) | Motores contra uma base mínima fixa: determinismo, equipamento, nível, teto de volume, tempo, segurança, progressão, PRs, tendências, ferramentas. |
| `knowledge` (531) | Integridade do banco real, cadeia de evidências, visões SQL, `CHECK`s, cascata do `user.db` **216 cenários de perfil** (6 ambientes × 3 níveis × 4 frequências × 3 objetivos) e **~300 cenários de prioridade por região** testados por propriedades. |
| `appcore` (137) | Fluxo do app sobre SQLite em memória: questionário → programa salvo e recarregado idêntico, triagem, dor, semana e treino perdido, treino → progressão → PR, retomar treino, pouco tempo/prontidão, troca, água/corpo/sono/comida/cardio, exportar e apagar dados, simulador; **90 combinações de respostas** do questionário. |
| `app` (39 + capturas) | Robolectric roda a Activity real: questionário só com toques até o treino concluído, todas as abas e telas de Mais, treino perdido A–D, triagem com alerta, prioridade escolhida pela Home, ver/trocar dias da semana, lembretes (alarme, notificação, reinício), figuras nas telas e animações (estados inicial e final). O fluxo completo também roda sobre o **SQLite do Android** (`AndroidSqlDatabase`), e o conhecimento lido por ele é comparado com o lido via JDBC. `-Pscreenshots` gera PNGs das telas. |
| `coach` (24) | Diálogos de várias trocas, segurança, cada intenção, e avaliação do entendimento em conjuntos de desenvolvimento e de teste. |
| `cli` (2) | Fumaça de ponta a ponta. |
