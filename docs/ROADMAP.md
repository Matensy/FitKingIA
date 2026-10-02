# Roadmap

## ✅ Fase 1 — Fundação (concluída)
Motor determinístico (`core`), banco de conhecimento com evidências (`knowledge`), IA local offline (`coach`), CLI. Detalhe em [VISAO.md](VISAO.md).

## ✅ Fase 2 — App Android (concluída, v0.1.0)
- `appcore` (lógica testável na JVM) + `app` (telas em código), APK montado sem AGP — [ADR 0004](adr/0004-apk-sem-agp.md).
- Questionário só de toque, programa gerado pelo motor, Hoje (pouco tempo, check-in, treino perdido A–D, água, sono), execução do treino (carga sugerida, aquecimento, anilhas, RIR, cronômetro, PRs, XP), Semana, Progresso (volume, 1RM, deload, recordes, peso/cintura, fotos), nutrição, cardio, mobilidade, suplementos, evidências, simulador, ferramentas, perfil, exportar e apagar dados.
- `user.db` no aparelho com o schema de `user.sql`; `fitness.db` empacotado nos assets e renovado a cada atualização do APK.
- Testes de UI com Robolectric, capturas de tela e checagem de API do Android 8 no build.

## Fase 3 — Próximos passos do app
- Criptografia do `user.db` (SQLCipher) e das fotos.
- Lembretes locais opcionais (água, treino do dia) com notificações.
- Calendário mensal e linha do tempo de PRs; mapa muscular desenhado.
- Exercícios de mobilidade guiados no banco (hoje a mobilidade é só registro).
- Correlações pessoais: sono × desempenho, prontidão × volume (sem diagnóstico).
- Chave de release e publicação.

## Fase 4 — Conhecimento
- Importar a TACO completa (conferindo contra o PDF original) e porções caseiras — hoje são 10 alimentos verificados.
- Ampliar exercícios (alvo: 300+), afirmações e regras.
- **Evidence Update Engine**: busca periódica de publicações → fila `evidence_updates` → revisão humana → nova `content_version`.

## Fase 5 — IA local (opcional)
- O `coach` segue disponível na CLI. Se um dia entrar no app, será como tela opcional, nunca no fluxo principal (o app é guiado por alternativas).
