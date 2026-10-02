# FitKingIA

**Sistema de apoio à decisão para treino, composição corporal e hábitos — com IA local e evidências rastreáveis.**

O FitKingIA não deixa uma IA "inventar treino". O treino sai de um **motor determinístico** (regras + banco de dados + evidências); a **IA local** conversa em português, entende o pedido e apresenta o que o motor decidiu — offline, no aparelho, sem custo por uso. Toda recomendação responde à pergunta *"por que você está me recomendando isso?"* com a cadeia **recomendação → regra → evidência → fonte**.

```
🔵 FATO        "O ACSM (2026) associou ≥10 séries/semana a mais hipertrofia."      → vem de fontes verificadas
🟢 REGRA       "Para o seu perfil, o motor definiu 8–16 séries/semana, alvo 12."   → decisão determinística, auditável
🟣 ASSISTENTE  "Entendi: você tem 35 minutos hoje."                                → fala da IA local; nunca decide prescrição
```

## O que já existe

| Módulo | O que faz |
|---|---|
| [`core`](core/) | Domínio puro em Kotlin (sem IO, sem Android). Motores: triagem de segurança, gerador de programa, seleção e substituição de exercícios, volume semanal com contagem fracionada, ajuste ao tempo ("tenho só 35 minutos"), agendamento com esportes, dupla progressão, PRs, tendência de 1RM, auto-deload, Recovery Score, fadiga, dashboard de volume, treino perdido (A–D), simulador "e se?", hidratação, nutrição, IMC/cintura-altura, flutuação de peso, anilhas, aquecimento, gamificação e "🔬 Por que isso?". |
| [`knowledge`](knowledge/) | Banco de conhecimento: seeds JSON revisáveis → `fitness.db` (SQLite). 105 exercícios, 23 padrões de movimento, 7 divisões, 35 fontes verificadas, 28 afirmações (2 com evidência conflitante), 18 regras parametrizadas, alimentos TACO, 5 suplementos. Validador de integridade e schema do banco do usuário (`user.sql`). |
| [`coach`](coach/) | **IA local**: entende português (classificador treinável + entidades), conversa com memória, pergunta o que falta, aprende com o usuário, responde dúvidas só com o banco de evidências e tem camada de segurança. Sem LLM pago. Ver [docs/IA_LOCAL.md](docs/IA_LOCAL.md). |
| [`cli`](cli/) | `fitking`: ferramenta de terminal que exercita todos os motores e o chat. |

O app Android (Kotlin + Jetpack Compose + Room) é a próxima fase — o `core` e o `coach` já são Kotlin puro, prontos para serem usados pelo app. Ver [docs/ROADMAP.md](docs/ROADMAP.md).

## Rodando

Requer JDK 17+.

```bash
./gradlew build                                   # compila e roda os 325 testes
./gradlew :knowledge:buildKnowledgeDb             # gera knowledge/build/fitness.db + relatório de integridade
./gradlew :cli:installDist                        # instala o fitking em cli/build/install/fitking

F=cli/build/install/fitking/bin/fitking
$F demo                                           # passeio por todas as funções
$F coach                                          # 💬 chat com a IA local
$F pergunta "tenho só 35 minutos hoje"
$F programa                                       # programa do perfil de exemplo
$F --perfil examples/perfil-exemplo.json programa # use seu próprio perfil
$F porque supino                                  # 🔬 regra → evidência → fonte
$F substituir agachamento joelho                  # substituições com dor relatada
$F simular                                        # 3 × 4 × 5 dias
$F fontes                                         # fontes científicas e conflitos
```

Exemplo de conversa (offline, saída real numa sexta-feira):

```
você › hoje estou sem tempo
🟣 Quanto tempo você tem hoje? (ex.: 35 minutos)

você › uns 35 minutos
🟣 Entendi: você tem 35 minutos.
🟢 ⚡ QUICK SESSION — Superiores B (sexta, ~35 min)
  1. Barra fixa supinada — 3 × 6–10, RIR 2, descanso 2 min
  2. Supino inclinado com barra — 3 × 6–10, RIR 2, descanso 2 min
  3. Remada máquina (apoio no peito) — 2 × 8–12, RIR 2, descanso 1min30s
  4. Crucifixo inverso na máquina — 2 × 10–15, RIR 1, descanso 1 min
O que mudou e por quê:
  • Removido: Rosca na polia — músculos principais (bíceps) já trabalhados por Barra fixa supinada
  • Removido: Peck deck (voador) — músculos principais (peitoral) já trabalhados por Supino inclinado com barra
  • Descanso no limite inferior da faixa prescrita — mantém o volume …

você › treinar até a falha é melhor?
🟣 O que o banco de evidências diz:
🔵 Treinar até a falha momentânea não mostrou superioridade clara para hipertrofia ou força em relação a
   terminar as séries com algumas repetições em reserva.
   Nível: Evidência moderada
   ✔ Grgic J (2022) — Effects of resistance training performed to repetition failure or non-failure … · doi:10.1016/j.jshs.2021.01.007
   ✔ Refalo MC (2023) — Influence of Resistance Training Proximity-to-Failure … · doi:10.1007/s40279-022-01784-y
```

## Princípios

1. **O motor decide, a IA explica.** Nenhuma prescrição sai de texto gerado.
2. **Toda afirmação tem fonte e data de verificação.** Fontes pendentes são marcadas como pendentes; números não verificados ficam vazios, nunca inventados.
3. **Evidência conflitante é mostrada, não resolvida à força.**
4. **Segurança antes de tudo.** Sinais de alerta interrompem a prescrição; o app não diagnostica.
5. **Local primeiro.** Dados pessoais no `user.db` do aparelho, separado do conhecimento; a IA não manda nada para fora.
6. **Determinismo e testes.** Mesma entrada → mesmo programa; 216 cenários de perfil testados por propriedades.

## Documentação

- [Visão e status das funcionalidades](docs/VISAO.md)
- [Arquitetura](docs/ARQUITETURA.md)
- [IA local](docs/IA_LOCAL.md)
- [Política de evidências](docs/EVIDENCIAS.md)
- [Segurança e privacidade](docs/SEGURANCA_E_PRIVACIDADE.md)
- [Roadmap](docs/ROADMAP.md)
- Decisões de arquitetura: [ADR 0001](docs/adr/0001-motor-deterministico.md) · [ADR 0002](docs/adr/0002-dois-bancos-sqlite.md) · [ADR 0003](docs/adr/0003-ia-local-sem-llm.md)

> ⚠️ O FitKingIA é uma ferramenta de apoio e educação. Não substitui avaliação médica, fisioterapêutica ou nutricional.
