# FitKingIA — guia para sessões de IA no repositório

- Idioma: código e identificadores em inglês; textos ao usuário, docs e commits em português (pt-BR, números com vírgula via `Fmt`).
- **Nunca** gere prescrição fora do `core`. A IA local (`coach`) só interpreta e chama motores.
- Parâmetros de regras ficam em `knowledge/src/main/resources/knowledge/rules.json`, não em constantes no código. Regra nova precisa de `basis`, `rationale` e (se `EVIDENCE`) afirmações com fonte.
- Não invente fontes, DOIs ou valores nutricionais. Sem verificação → `verification: PENDING` ou campo `null`.
- Depois de editar JSON do conhecimento: `./gradlew :knowledge:buildKnowledgeDb` (relatório) e `./gradlew build`.
- IA local: mais exemplos em `coach/src/main/resources/coach/intents.json`; nunca ajuste o modelo olhando `intent_test.tsv`.
- Nomes de testes com acento: não crie lambdas/referências de função dentro deles (gera `.class` com acento) — declare fora.
- Toda resposta/explicação marca proveniência: 🔵 fato, 🟢 regra, 🟣 assistente.
