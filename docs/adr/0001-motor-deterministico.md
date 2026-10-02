# ADR 0001 — O treino sai de um motor determinístico, não de texto gerado

**Status:** aceita · 2026-10-02

## Contexto
Um modelo de linguagem consegue escrever treinos plausíveis, mas não garante que respeitem equipamento, nível, tempo, dor relatada, teto de volume ou as evidências — e não consegue explicar de forma auditável por que escolheu cada número.

## Decisão
Toda prescrição (dias, divisão, exercícios, séries, reps, RIR, descanso, carga) é calculada por código determinístico no módulo `core`, parametrizado por regras do banco de conhecimento. A camada de conversa só interpreta pedidos e apresenta o que o motor decidiu.

## Consequências
- Mesma entrada → mesmo programa; testável por propriedades (216 cenários).
- Cada decisão carrega `RuleId` e chega até as fontes ("Por que isso?").
- Mudar a ciência = editar regras/afirmações no banco, não reescrever código.
- O motor é mais rígido que um texto livre; casos novos exigem regras ou templates novos.
