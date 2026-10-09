# Visão e status das funcionalidades

O FitKingIA é um **sistema de apoio à decisão** para treino, composição corporal, nutrição, hidratação e recuperação: um app Android guiado só por alternativas, sete motores determinísticos e um banco de conhecimento com evidências.

Legenda: ✅ implementado e testado · 🟡 parcial (domínio/banco prontos, falta interface ou expansão) · ⬜ planejado

## Motores

| Área | Status | Onde |
|---|---|---|
| **Perfil & avaliação**: dados, objetivos (composição, performance, saúde), região a priorizar, experiência, ambiente, equipamentos, disponibilidade por dia, esportes, limitações | ✅ questionário de 20 passos só por toque | `appcore/Questionnaire.kt`, `QuestionnaireScreen` |
| **Prioridade por região** (glúteos, pernas, costas, peito, ombros, braços, abdômen; até 2): metas maiores para a região, manutenção para o resto, frequência mínima (inferiores 3× com 3+ dias), exercícios com foco na região no começo do treino; modelos com ênfase em glúteos (2–6 dias) e em pernas/coxas (3–5 dias) | ✅ | regra `priority.region`, `VolumeTargets`, `VolumePlanner`, `ProgramGenerator` |
| **"Seu objetivo × seu treino"**: confere em números se o programa treina o que a pessoa pediu (dias, séries focadas por músculo, fatia das séries, ordem) e avisa quando não dá | ✅ | `GoalAlignment`, `GoalViews` |
| **Questionário de segurança** com red flags que bloqueiam a prescrição | ✅ | `core/safety`, `safety_questions.json` |
| **"Perder barriga"** como objetivo, com explicação sobre perda localizada e evidência conflitante | ✅ | `Goal.spotReductionNotice`, claim `spot_reduction` |
| **Gerador de programa** por dias e tempo; recalcula ao mudar a disponibilidade | ✅ | `core/program` |
| **"Tenho só 40 minutos"** — treino rápido explicando o que saiu e por quê | ✅ | `SessionFitter`, `SessionAdapter.forTime` |
| **Banco de exercícios** (125) com padrão, músculos, foco curado, equipamento (40 aparelhos/acessórios), dificuldade, estabilidade, mobilidade, demanda articular, instruções, erros comuns, segurança, progressão, substituições | ✅ (meta 300+) | `exercises.json`, `equipment.json` |
| **Ilustrações animadas** dos exercícios (sem fotos de terceiros): 119 movimentos, todos os exercícios mapeados | ✅ | `app/figure`, `ExerciseScreen`, `WorkoutScreen` |
| **23 padrões de movimento** (empurrar/puxar H/V, agachar, dobradiça, afundo, carregar, antirrotação, antiextensão…) | ✅ | `patterns.json` |
| **Substituição** por padrão, músculos, equipamento, nível, estabilidade, dificuldade, dor, curadoria e histórico | ✅ | `SubstitutionEngine` |
| **Prescrição**: séries, faixa de reps/tempo, RIR, descanso, cadência, aquecimento | ✅ | regra `prescription.reps_rir_rest`, `WarmupGenerator` |
| **Progressive overload**: dupla progressão, RIR, redução após falhas, padrão aprendido do usuário | ✅ | `ProgressionEngine` |
| **PRs** (carga, reps na carga, 1RM estimado, volume) e **tendência** de 1RM | ✅ | `Records.kt` |
| **Dashboard de volume** semanal por músculo com mensagens | ✅ | `VolumeDashboard` |
| **Autorregulação**: avaliação do dia → índice de recuperação → ajuste do treino | ✅ | `core/recovery`, `SessionAdapter.forReadiness` |
| **Fadiga por músculo** (inclui esportes) | ✅ | `FatigueModel` |
| **Esportes** (kickboxing etc.) no agendamento da semana | ✅ | `WeekScheduler`, `sports.json` |
| **Dor/lesões**: sem diagnóstico, filtros por articulação, substituições | ✅ | `JointLimitation`, `SubstitutionEngine` |
| **Treino perdido** (A mover · B incorporar · C ignorar · D recalcular) | ✅ | `MissedWorkoutPlanner` |
| **Simulador "E se?"** (dias, tempo, casa × academia) | ✅ | `ProgramSimulator` |
| **Semana de descarga (deload)** sugerida por queda persistente + prontidão baixa | ✅ | `DeloadAdvisor` |
| **Hidratação**: meta, registro +250/+500 ml | ✅ | `core/hydration` |
| **Lembretes locais** (água abaixo do ritmo, treino do dia, sequência), sem servidor | ✅ | `appcore/Reminders.kt`, `app/notify`, `NotificationsScreen` |
| **Nutrição**: metas de energia/proteína, registro por toque (alimento + porções); na CLI também em linguagem natural | ✅ (10 alimentos TACO; expandir) | `core/nutrition`, `NutritionScreen` |
| **Suplementos**: informação baseada em evidência, sem prescrição | ✅ | `supplements.json` |
| **Composição corporal**: IMC e cintura/altura com ressalvas | ✅ · ⬜ massa muscular estimada | `BodyMetrics` |
| **"Desinchar"** — flutuação de peso vs. tendência | ✅ | `WeightTrend` |
| **Tracker corporal** (peso, cintura) com gráfico e média móvel | ✅ | `BodyLogScreen`, `ProgressScreen` |
| **Progresso visual** (fotos frente/lado/costas, só no aparelho) | ✅ | `PhotosScreen` |
| **Sono**: registro, média e noites ≥ 7 h; entra no check-in | ✅ · ⬜ correlações pessoais | `SleepScreen` |
| **Cardio**: registro e minutos da semana vs. OMS | ✅ | `CardioScreen` |
| **Mobilidade** | 🟡 registro por região · ⬜ exercícios guiados | `MobilityScreen` |
| **Gamificação** discreta (XP, nível, streak) | ✅ | `core/gamification` |
| **Ferramentas**: anilhas, 1RM, volume, aquecimento | ✅ | `core/tools`, `OneRepMax` |
| **Timer de descanso** (vibração + som) | ✅ | `WorkoutScreen` |
| **Semana** com status por dia (feito, perdido, replanejado); semanas anteriores (só consulta) e a próxima; qualquer dia abre o treino; fazer hoje o treino de outro dia; trocar dias de hoje em diante ou na próxima semana (só nessa semana ou todas), com desfazer | ✅ · ⬜ calendário mensal | `WeekScreen`, `DayScreen`, `appcore/WeekReorder.kt` |
| **Animações** de navegação, toque, cronômetro em anel e comemoração (respeitam "remover animações") | ✅ | `app/ui/Motion.kt` |
| **Mapa muscular** | 🟡 barras de volume por músculo · ⬜ desenho | `ProgressScreen` |

## Evidências e IA

| Item | Status |
|---|---|
| Banco de evidências: fonte de cada afirmação, data de verificação, tiers S/A/B | ✅ |
| "🔬 Por que isso?" — recomendação → regra → evidência → fonte | ✅ `WhyService` |
| Conflito entre estudos exibido, sem consenso inventado | ✅ |
| Níveis de evidência (alta, moderada, limitada, inconclusiva) | ✅ |
| Fontes brasileiras (Ministério da Saúde: atividade física e alimentar; TACO) | ✅ |
| Evidence Update Engine | 🟡 tabela `evidence_updates` e `v_sources_to_verify`; ⬜ pipeline |
| **AI Coach local** (offline, sem custo): entende português, conversa, aprende, nunca prescreve sozinho | ✅ `coach` na CLI · fora do app por decisão de produto |
| Separação 🔵 FATO / 🟢 REGRA / 🟣 ASSISTENTE em todas as respostas | ✅ |

## Engenharia

| Item | Status |
|---|---|
| Testes de unidade, integração, banco, motor de regras, geração (216 cenários), prioridade por região (~300 cenários), questionário (90 combinações), UI (Robolectric), regressão e diálogo | ✅ ~900 testes |
| Banco distribuído não vazio (`fitness.db`) separado do `user.db` | ✅ |
| Privacidade: local, consentimento, exportar (JSON) e apagar dados, sem permissão de internet | ✅ · ⬜ criptografia (SQLCipher) |
| App Android (Kotlin, telas em código, SQLite do sistema) — APK em `dist/` | ✅ v0.2.0 — ver [ADR 0004](adr/0004-apk-sem-agp.md) |
| Checagem de API do Android 8 no build | ✅ `:app:checkAndroidApi` |
