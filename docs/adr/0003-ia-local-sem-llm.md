# ADR 0003 — IA local, offline e sem LLM pago

**Status:** aceita · 2026-10-02

## Contexto
O assistente precisa conversar em português sobre o treino, mas o projeto não deve depender de API paga por token, internet ou envio de dados de saúde para terceiros. Uma versão anterior começou a integrar um LLM em servidor e foi descartada por decisão do dono do produto.

## Decisão
O `coach` é uma IA local em Kotlin puro: NLU (Naive Bayes + entidades + regras de alta precisão), gerenciador de diálogo com memória e aprendizado no aparelho, camada de segurança e respostas montadas a partir dos motores e do banco de evidências (busca TF-IDF).

## Consequências
- Custo zero por uso, funciona offline, dados não saem do aparelho.
- Comportamento previsível e testável; qualidade medida em conjunto de teste (94% de acerto do classificador; 4% de erros silenciosos no pipeline).
- Não conversa livremente fora do domínio; respostas são estruturadas, não "criativas".
- Caminho aberto para, no futuro, um modelo pequeno no aparelho apenas para reescrita de texto — sem nunca virar fonte de prescrição.
