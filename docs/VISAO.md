# Visão e status das funcionalidades

O FitKingIA é um **sistema de apoio à decisão** para treino, composição corporal, nutrição, hidratação e recuperação. Sete motores, um banco de conhecimento com evidências e uma IA local que conversa.

Legenda: ✅ implementado e testado · 🟡 parcial (domínio/banco prontos, falta interface ou expansão) · ⬜ planejado

## Motores

| Área | Status | Onde |
|---|---|---|
| **Perfil & avaliação**: dados, objetivos (composição, performance, saúde), experiência, ambiente, equipamentos, disponibilidade por dia, esportes, limitações | ✅ domínio · ⬜ telas | `core/model/Profile.kt`, `user.sql` |
| **Questionário de segurança** com red flags que bloqueiam a prescrição | ✅ | `core/safety`, `safety_questions.json` |
| **"Perder barriga"** como objetivo, com explicação sobre perda localizada e evidência conflitante | ✅ | `Goal.spotReductionNotice`, claim `spot_reduction` |
| **Gerador de programa** por dias e tempo; recalcula ao mudar a disponibilidade | ✅ | `core/program` |
| **"Tenho só 40 minutos"** — Quick Session explicando o que saiu e por quê | ✅ | `SessionFitter`, `SessionAdapter.forTime` |
| **Banco de exercícios** (105) com padrão, músculos, equipamento, dificuldade, estabilidade, mobilidade, demanda articular, instruções, erros comuns, segurança, progressão, substituições | ✅ (meta 300+) | `exercises.json` |
| **23 padrões de movimento** (empurrar/puxar H/V, agachar, dobradiça, afundo, carregar, antirrotação, antiextensão…) | ✅ | `patterns.json` |
| **Substituição** por padrão, músculos, equipamento, nível, estabilidade, dificuldade, dor, curadoria e histórico | ✅ | `SubstitutionEngine` |
| **Prescrição**: séries, faixa de reps/tempo, RIR, descanso, cadência, aquecimento | ✅ | regra `prescription.reps_rir_rest`, `WarmupGenerator` |
| **Progressive overload**: dupla progressão, RIR, redução após falhas, padrão aprendido do usuário | ✅ | `ProgressionEngine` |
| **PRs** (carga, reps na carga, 1RM estimado, volume) e **tendência** de 1RM | ✅ | `Records.kt` |
| **Dashboard de volume** semanal por músculo com mensagens | ✅ | `VolumeDashboard` |
| **Autorregulação**: check-in → Recovery Score → ajuste do treino | ✅ | `core/recovery`, `SessionAdapter.forReadiness` |
| **Fadiga por músculo** (inclui esportes) | ✅ | `FatigueModel` |
| **Esportes** (kickboxing etc.) no agendamento da semana | ✅ | `WeekScheduler`, `sports.json` |
| **Dor/lesões**: sem diagnóstico, filtros por articulação, substituições | ✅ | `JointLimitation`, `SubstitutionEngine` |
| **Treino perdido** (A mover · B incorporar · C ignorar · D recalcular) | ✅ | `MissedWorkoutPlanner` |
| **Program simulator / "E se?"** (dias, tempo, casa × academia) | ✅ | `ProgramSimulator` |
| **Auto-deload** por queda persistente + prontidão baixa | ✅ | `DeloadAdvisor` |
| **Hidratação**: meta, registro +250/+500 ml | ✅ | `core/hydration` |
| **Nutrição**: metas de energia/proteína, registro em linguagem natural ("comi arroz, feijão…") | ✅ (10 alimentos TACO; expandir) | `core/nutrition`, `foods.json` |
| **Suplementos**: informação baseada em evidência, sem prescrição | ✅ | `supplements.json` |
| **Composição corporal**: IMC e cintura/altura com ressalvas | ✅ · ⬜ massa muscular estimada | `BodyMetrics` |
| **"Desinchar"** — flutuação de peso vs. tendência | ✅ | `WeightTrend` |
| **Tracker corporal** (medidas) | 🟡 modelo e schema; ⬜ gráficos | `BodyMeasurement`, `body_measurements` |
| **Progresso visual** (fotos) | 🟡 schema | `progress_photos` |
| **Sono** com correlações pessoais | 🟡 schema + entra no Recovery Score | `sleep_logs` |
| **Cardio** | 🟡 schema + recomendação OMS | `cardio_sessions` |
| **Mobilidade** | 🟡 schema | `mobility_sessions` |
| **Gamificação** discreta (XP, nível, streak) | ✅ | `core/gamification` |
| **Ferramentas**: anilhas, 1RM, volume, aquecimento | ✅ | `core/tools`, `OneRepMax` |
| **Calendário, timer de descanso, mapa muscular** | ⬜ (Android) | — |

## Evidências e IA

| Item | Status |
|---|---|
| Banco de evidências: fonte de cada afirmação, data de verificação, tiers S/A/B | ✅ |
| "🔬 Por que isso?" — recomendação → regra → evidência → fonte | ✅ `WhyService` |
| Conflito entre estudos exibido, sem consenso inventado | ✅ |
| Níveis de evidência (alta, moderada, limitada, inconclusiva) | ✅ |
| Fontes brasileiras (Ministério da Saúde: atividade física e alimentar; TACO) | ✅ |
| Evidence Update Engine | 🟡 tabela `evidence_updates` e `v_sources_to_verify`; ⬜ pipeline |
| **AI Coach local** (offline, sem custo): entende português, conversa, aprende, nunca prescreve sozinho | ✅ `coach` |
| Separação 🔵 FATO / 🟢 REGRA / 🟣 ASSISTENTE em todas as respostas | ✅ |

## Engenharia

| Item | Status |
|---|---|
| Testes de unidade, integração, banco, motor de regras, geração (216 cenários), regressão e diálogo | ✅ 325 testes |
| Banco distribuído não vazio (`fitness.db`) separado do `user.db` | ✅ |
| Privacidade: local, consentimento, apagar dados | ✅ schema · ⬜ criptografia/exportação no Android |
| App Android (Kotlin + Compose + Room) | ⬜ Fase 2 — ver [ROADMAP.md](ROADMAP.md) |
