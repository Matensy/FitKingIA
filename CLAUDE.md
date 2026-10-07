# FitKingIA — guia para sessões de IA no repositório

- Idioma: código e identificadores em inglês; textos ao usuário, docs e commits em português (pt-BR, números com vírgula via `Fmt`).
- **Nunca** gere prescrição fora do `core`. A IA local (`coach`) só interpreta e chama motores.
- Parâmetros de regras ficam em `knowledge/src/main/resources/knowledge/rules.json`, não em constantes no código. Regra nova precisa de `basis`, `rationale` e (se `EVIDENCE`) afirmações com fonte.
- Não invente fontes, DOIs ou valores nutricionais. Sem verificação → `verification: PENDING` ou campo `null`.
- Depois de editar JSON do conhecimento: `./gradlew :knowledge:buildKnowledgeDb` (relatório) e `./gradlew build`.
- IA local: mais exemplos em `coach/src/main/resources/coach/intents.json`; nunca ajuste o modelo olhando `intent_test.tsv`.
- Nomes de testes com acento: não crie lambdas/referências de função dentro deles (gera `.class` com acento) — declare fora.
- Toda resposta/explicação marca proveniência: 🔵 fato, 🟢 regra, 🟣 assistente.
- App Android: lógica nova vai no `appcore` (testável na JVM); `app` só desenha telas e chama `FitKing`. Telas em código com o kit de `app/ui` (sem XML/AndroidX).
- O app compila contra `android.jar` da API 23: getters adicionados depois não viram propriedade Kotlin (use `setGravity`, `setCornerRadius`). Evite APIs Java 9+ (`List.of`, `removeLast`…): `./gradlew :app:apk` roda `checkAndroidApi` e falha se aparecer.
- SQL do `user.db` precisa rodar no SQLite do Android 8 (3.18): sem UPSERT, sem funções JSON, sem window functions.
- `user.db` mudou de esquema? Suba `UserDb.VERSION`, acrescente o passo (idempotente) em `UserDb.migrate` e um teste migrando um banco da versão anterior com dados.
- Exercício novo no banco: preencha `focus` quando o foco não for todos os músculos principais e confira a figura em `app/figure/FigureMapping.kt` (folhas `figuras_*.png` com `-Pscreenshots`).
- Testes de UI desligam animações (`Motion.enabled = false` no `setUp`); só `MotionTest` e `ScreenshotTest` ligam.
- Prioridade por região: parâmetros em `rules.json` (`priority.region`); confira com `PriorityScenarioTest` e a CLI (`--perfil examples/perfil-gluteos.json programa`) que a checagem "objetivo × treino" bate com o treino impresso.
- Antes de entregar mudança de tela: `./gradlew :app:test` (Robolectric) e, se mexeu em layout, `./gradlew :app:test -Pscreenshots` e olhe os PNGs.
