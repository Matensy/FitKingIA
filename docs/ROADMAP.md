# Roadmap

## ✅ Fase 1 — Fundação (concluída)
Motor determinístico (`core`), banco de conhecimento com evidências (`knowledge`), IA local offline (`coach`), CLI e 325 testes. Detalhe em [VISAO.md](VISAO.md).

## Fase 2 — App Android
- Módulo `app` com Kotlin + Jetpack Compose; arquitetura UI → ViewModel → Use Cases (`core`/`coach`) → Repositórios → Room.
- `fitness.db` empacotado em `assets/` (gerado pelo Gradle) e lido via Room (`createFromAsset`); `user.db` com Room + SQLCipher.
- Telas: splash, onboarding, avaliação inicial e triagem, objetivos, equipamentos, disponibilidade, dashboard, treino de hoje, execução (timer de descanso, registro de séries com RIR), histórico, progressão/PRs, corpo e fotos, nutrição, água, sono, cardio, mobilidade, suplementos, evidências, coach, configurações (consentimentos, exportar/apagar dados).
- Persistência das `CoachAction` (água, refeição) e do aprendizado do classificador por usuário.

## Fase 3 — Módulos que hoje têm só schema
- **Sono**: registro e correlação com desempenho do próprio usuário (sem diagnóstico).
- **Cardio**: minutos semanais vs. OMS (150–300 min), PRs de distância/tempo, zona 2/HIIT.
- **Mobilidade**: testes simples e exercícios de mobilidade no banco.
- **Progresso visual**: fotos frente/costas/lado com guia de iluminação/distância/pose e linha do tempo.
- **Calendário** mensal e linha do tempo de PRs.

## Fase 4 — Conhecimento
- Importar a TACO completa (conferindo contra o PDF original) e porções caseiras.
- Ampliar exercícios (alvo: 300+), afirmações e regras.
- **Evidence Update Engine**: busca periódica de publicações → fila `evidence_updates` → revisão humana → nova `content_version`.

## Fase 5 — IA local
- Mais frases reais no corpus a partir do uso (com consentimento) e novo conjunto de teste a cada versão.
- Múltiplas intenções por mensagem.
- Opcional e configurável: modelo de linguagem pequeno rodando no aparelho só para reescrever respostas, mantendo motor e evidências como fonte da verdade.
