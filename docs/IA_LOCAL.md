# IA local (coach offline)

O assistente do FitKingIA **não usa LLM pago nem internet**. Ele roda no aparelho, em Kotlin puro (`coach`), e funciona em quatro camadas:

```
mensagem ──► 1. segurança ──► 2. entendimento ──► 3. diálogo ──► 4. resposta (motores + evidências)
```

## 1. Camada de segurança
Antes de qualquer coisa, padrões de alerta são verificados no texto normalizado:

| Situação | Resposta |
|---|---|
| Dor/aperto no peito, desmaio, falta de ar desproporcional, palpitações, formigamento no braço | "Pare o exercício… procure atendimento — emergência 192 (SAMU)". Nenhum conselho de treino. |
| Menção a autolesão | Acolhimento + CVV 188 (24 h) e 192. |
| Estratégias de risco (vômito, laxante, dias sem comer) | Não orienta; sugere apoio profissional e CVV. |
| Hormônios, anabolizantes, remédios para emagrecer | Não orienta; exige acompanhamento médico. |

## 2. Entendimento (NLU em português)

- **Texto** (`PtText`): minúsculas, sem acento, stopwords, stemmer leve (estilo RSLP), trigramas de caracteres e distância de Levenshtein para tolerar erros ("agaxamento", "joleho").
- **Entidades** (`EntityExtractor`): dias ("hoje", "amanhã", "quarta-feira"), tempo ("meia hora", "1h30", "35 min"), exercícios (nomes e apelidos do banco, tolerando erros, preferindo os do seu programa), músculos ("peito", "posterior", "bumbum"), articulação e intensidade da dor ("dor 7/10", "forte"), carga e repetições ("100 kg x 5"), água ("1 copo", "500 ml"), sinais de cansaço ("dormi mal", "acabado", "sem gás"), equipamento ("sem barra", "em casa"), suplementos, "e se…".
- **Intenções** (`IntentModel`): Naive Bayes multinomial treinado no corpus `coach/src/main/resources/coach/intents.json` (≈500 frases em 26 intenções). Atributos: radicais, bigramas, trigramas de caracteres e **etiquetas de entidades** (ex.: `t:supplement` quando a frase cita creatina). Treina em milissegundos na inicialização.
- **Regras de alta precisão** corrigem o classificador em padrões inequívocos (ex.: "82,5 kg na barra" → anilhas; "faltei" → treino perdido; "meu peso subiu" → variação de peso, não carga).

## 3. Diálogo

- **Memória curta** (`ConversationState`): "como faço stiff?" → "pode trocar?" entende que é o stiff.
- **Dado faltando**: "hoje estou sem tempo" → *"Quanto tempo você tem hoje?"* → "uns 30 minutos" completa o pedido original.
- **Dúvida real**: se a confiança é baixa, pergunta entre as duas intenções mais prováveis.
- **Aprendizado no aparelho**: quando o usuário escolhe uma opção, a frase original vira exemplo daquela intenção (`IntentModel.learn`). Da próxima vez, entende direto.
- **Ações propostas**: registrar água ou refeição volta como `CoachAction`; o app confirma e grava no `user.db`. A IA não escreve dados sozinha.

## 4. Resposta

Cada intenção chama um motor do `core` (gerador, `SessionAdapter`, `SubstitutionEngine`, `ProgressionEngine`, `WhyService`, simulador, `MealParser`…). Dúvidas científicas usam `EvidenceSearch` (TF-IDF + cosseno sobre as afirmações e resumos das fontes): a resposta traz a afirmação, o nível de evidência, se há conflito e as fontes. **Sem resultado no banco, a IA diz que não sabe** em vez de inventar.

Intenções suportadas: treino do dia/semana, pouco tempo, trocar exercício, dor, cansaço, "por que esse exercício/dia", como executar, próxima carga, encontrar exercício, simular rotina, treino perdido, anilhas, 1RM, refeição, água, calorias/proteína, suplementos, perder barriga, dúvidas científicas, IMC/cintura, variação de peso, volume da semana, cumprimento, ajuda, agradecimento.

## Qualidade medida

Dois conjuntos de frases que **não estão no treino** (`coach/src/test/resources/coach/`):

| Conjunto | Uso | Classificador | Pipeline completo |
|---|---|---|---|
| `intent_dev.tsv` (81 frases) | análise de erros durante o ajuste | 97,5% | 76 certas · 5 esclarecimentos · 0 erros silenciosos |
| `intent_test.tsv` (50 frases) | **teste independente**, escrito depois do ajuste e não usado para mudar o modelo | **94,0%** | **45 certas · 3 esclarecimentos · 2 erros silenciosos** |

"Erro silencioso" = respondeu outra coisa sem perguntar. Os testes falham se o pipeline cair abaixo de 85% de acerto ou passar de 8% de erros silenciosos.

## Como melhorar a IA

1. Adicione frases reais em `intents.json` (variações informais, gírias, erros de digitação).
2. Rode `./gradlew :coach:test` e veja a acurácia impressa e os erros listados.
3. Corrija com mais exemplos antes de criar regras; regras só para padrões inequívocos.
4. **Não ajuste olhando o `intent_test.tsv`** — ele é a medida honesta. Crie novas frases de teste periodicamente.

## Limites conhecidos

- Não é um modelo de linguagem: entende pedidos dentro do domínio do app e responde com textos montados a partir dos motores; conversa livre fora do tema cai na ajuda.
- Uma intenção por mensagem.
- O banco de alimentos ainda é pequeno (10 itens da TACO); alimentos desconhecidos são listados como não reconhecidos.

Ver [ADR 0003](adr/0003-ia-local-sem-llm.md) para o porquê dessa escolha.
