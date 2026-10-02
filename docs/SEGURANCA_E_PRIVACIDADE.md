# Segurança e privacidade

## Segurança do usuário

**Triagem antes do primeiro treino** (`SafetyScreening`, perguntas em `safety_questions.json`, redação própria inspirada nos componentes da triagem pré-participação da ACSM — Riebe et al., 2015):

| Resposta "sim" | Resultado |
|---|---|
| Dor no peito, desmaio/tontura no esforço, falta de ar desproporcional/palpitações, restrição médica, cirurgia < 3 meses, gestação ou pós-parto < 6 meses | **Sem prescrição normal** — orientação para procurar um profissional. |
| Doença cardiovascular/metabólica/renal, pressão não controlada, medicação que altera a resposta ao esforço, dor atual | Programa gerado **com cautela**: avisos e filtros. |
| Limitação de mobilidade | Informativo: reduz exercícios com alta demanda de mobilidade. |

Também: menores de 18 anos são encaminhados (recomendações próprias da OMS); questionário incompleto não libera.

**Dor relatada**: o app nunca diagnostica. Cada exercício tem demanda 0–3 por articulação; a dor informada (0–10) define o máximo tolerado (leve exclui demanda alta; moderada exclui média; forte ≥ 7 exclui tudo que usa a articulação e orienta procurar avaliação).

**IA local**: camada de segurança antes do entendimento (ver [IA_LOCAL.md](IA_LOCAL.md)) — sinais de alerta, autolesão (CVV 188), estratégias de risco e hormônios.

**Suplementos**: só informação baseada em evidência, sempre com "não é recomendação individual".

## Privacidade (LGPD)

| Princípio | Como está implementado |
|---|---|
| Local por padrão | Dados pessoais só no `user.db` do aparelho. O APK não pede permissão de internet (só vibração, para o fim do descanso). |
| Separação | `fitness.db` (conhecimento, sem dados pessoais) é separado do `user.db`; atualizar conteúdo nunca toca dados do usuário. |
| Consentimento | Tabela `consents` granular e auditável (armazenamento, coach, fotos, analytics, backup). |
| Minimização | O coach lê o perfil localmente e não registra conversas sem consentimento (`ai_messages`). |
| Apagar dados | `ON DELETE CASCADE` em todas as tabelas: remover o usuário remove tudo (testado). |
| Exportar | Mais → Perfil e dados → "Exportar meus dados": todas as tabelas do `user.db` em JSON, salvo onde o usuário escolher (sem permissões). |
| Fotos | Copiadas para o armazenamento privado do app; não vão para a galeria nem saem do aparelho; apagadas junto com os dados. |
| Backup | `allowBackup=false`: o Android não copia o `user.db` para a nuvem. |
| Criptografia | Planejado: SQLCipher para o `user.db` e as fotos. |
| Logs | Sem dados pessoais em logs; `recommendation_log` guarda só regra, versão e conteúdo da recomendação. |
