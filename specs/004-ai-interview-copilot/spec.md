# Spec 004 — Copiloto de entrevistas técnicas

Prioridade: **P1** · Status: **Rascunho, aguardando aprovação** · Problema: P-14

## Contexto e problema
- **Gatilho errado:** o prompt atual manda gerar uma resposta completa sempre que um conceito técnico for *mencionado*.
- **Mistura de tarefas:** a resposta sai no mesmo JSON da análise incremental (resumo, tarefas, decisões), sem estrutura própria, sem versão para falar e sem distinguir pergunta de comentário ou de resposta do candidato.
- **Simulação (sem chave):** usa respostas fixas por palavra-chave e consulta a Wikipédia com a fala da entrevista.

## Objetivo
Para cada pergunta detectada (spec 005), gerar **uma** sugestão de resposta que o candidato possa falar com naturalidade. A sugestão deve ser tecnicamente correta, contextualizada, na profundidade certa e fiel ao currículo, sem inventar experiência.

## Escopo
- Prompt e contrato de saída dedicados ao copiloto, separados da análise incremental.
- Uso do contexto da conversa e da Base de Conhecimento (já existente).
- Estrutura adaptativa da resposta.
- Exibição na UI (versão para falar + detalhes).
- Comportamento da simulação sem chave.
- Regeneração sob demanda.

## Fora de escopo
- Detecção de perguntas (spec 005).
- Streaming de tokens da resposta (otimização P3).
- Escolha automática de modelo por custo (P3).

## Requisitos funcionais
- **RF-01** O copiloto é chamado **apenas** para `InterviewQuestion` com status `DETECTED` vinda da spec 005, ou pelo gatilho manual.
- **RF-02** Entrada do prompt:
  - Pergunta.
  - Últimos 8 segmentos (com papel do falante).
  - Resumo da reunião.
  - Tipo e pontuação da pergunta.
  - `<knowledge_base>` (currículo + trechos de referência, já existente).
  - Papel do usuário (candidato).
- **RF-03** Saída em JSON validado:
  - `is_question_for_candidate` (bool): a confirmação do LLM.
  - `depth`: `BASIC`, `INTERMEDIATE` ou `ADVANCED`.
  - `spoken_answer`: 60-140 palavras, primeira pessoa, linguagem falada.
  - `sections`: `direct_answer`, `technical_explanation`, `practical_example`, `best_practices`, `trade_offs` (cada uma opcional, conforme a profundidade).
  - `resume_facts_used[]`: trechos do currículo efetivamente usados.
  - `assumptions[]` e `confidence` (0-1).
- **RF-04** Para os temas Java, Spring Boot, arquitetura, microsserviços, mensageria, SQL, sistemas distribuídos, concorrência e engenharia de software, a resposta inclui ao menos `direct_answer` + `technical_explanation` + (`practical_example` ou `trade_offs`).
- **RF-05** Se `is_question_for_candidate=false`, a pergunta vira `DISMISSED` e nenhum card é exibido. O registro fica disponível para auditoria.
- **RF-06** Experiência pessoal só pode citar fatos presentes no `<knowledge_base>`. Sem fato aplicável, a resposta trata o tema de forma conceitual, **sem** afirmar experiência, e indica em `assumptions` que não há exemplo no currículo.
- **RF-07** Um card de insight `QUESTION` por pergunta, vinculado por `question_id`. Uma nova versão (regeneração ou crescimento da pergunta) atualiza o mesmo card (`answer_version`+1).
- **RF-08** Na UI, o card mostra `spoken_answer` em destaque e as seções expansíveis, com os botões "Gerar de novo", "Não era pergunta" e "Copiar".
- **RF-09** Simulação (sem chave de IA): a resposta vem da Base de Conhecimento e das respostas técnicas locais já existentes, marcada visivelmente como **simulação**. **Sem** consulta à Wikipédia.
- **RF-10** O prompt da análise incremental (resumo, tarefas, decisões) deixa de gerar respostas técnicas: a instrução "sempre que mencionar um conceito técnico" é removida.

## Requisitos não funcionais
- **RNF-01** Latência entre o fim da pergunta e o card na tela: alvo p50 ≤ 6 s e p90 ≤ 12 s com Gemini Flash-Lite ou Grok sem raciocínio (meta a medir; não validada só com mocks).
- **RNF-02** JSON inválido → 1 nova tentativa pedindo correção do formato; persistindo, status `FAILED`, sem quebrar o fluxo.
- **RNF-03** Custo: 1 chamada por pergunta (dedup na spec 005).
- **RNF-04** Prompts versionados em arquivos de recurso (`prompts/interview-answer.v1.md`), com testes de contrato.

## Regras de negócio
- **RN-01** Nunca atribuir ao candidato tecnologia, empresa, cargo, número ou período ausente do currículo.
- **RN-02** A profundidade acompanha a pergunta: conceito simples → `BASIC` (resposta curta); desenho de sistema → `ADVANCED` (com trade-offs).
- **RN-03** O conteúdo da Base de Conhecimento é dado, nunca instrução (regra já existente).

## Fluxos principais
1. Pergunta "Explique SOLID" → copiloto → `INTERMEDIATE`, resposta falada com 5 princípios + exemplo do currículo (se houver) → card.
2. Pergunta "Como você garante consistência entre microsserviços?" → `ADVANCED` → Outbox/Saga + trade-offs + exemplo real do currículo (Transactional Outbox no OrionPay, por exemplo).
3. Comentário classificado como pergunta por engano → LLM devolve `is_question_for_candidate=false` → `DISMISSED`.

## Cenários de erro
| Cenário | Comportamento |
|---|---|
| Todos os provedores de LLM falham | Pergunta `FAILED`; card com "Tentar novamente" |
| JSON inválido duas vezes | `FAILED` com motivo `PARSE_ERROR` |
| Recusa do modelo (refusal) | `FAILED` com motivo `REFUSAL` |
| Base de Conhecimento vazia | Resposta conceitual, sem experiência pessoal (RF-06) |

## Critérios de aceite
- **CA-01** *Dado* uma pergunta técnica detectada e um currículo sem Kafka, *quando* o copiloto responde, *então* nenhum trecho afirma experiência com Kafka (verificado contra uma resposta mockada que viola a regra: o validador rejeita).
- **CA-02** *Dado* uma pergunta de arquitetura, *quando* a resposta é válida, *então* contém `direct_answer`, `technical_explanation` e (`practical_example` ou `trade_offs`).
- **CA-03** *Dado* uma resposta do LLM com JSON inválido e depois válido, *quando* processada, *então* há 2 chamadas e o card é exibido.
- **CA-04** *Dado* `is_question_for_candidate=false`, *quando* processado, *então* a pergunta fica `DISMISSED` e nenhum insight é publicado.
- **CA-05** *Dado* "Gerar de novo", *quando* acionado, *então* o mesmo card é atualizado com `answer_version=2`.
- **CA-06** *Dado* a simulação (sem chave), *quando* uma pergunta é respondida, *então* o card mostra o selo "simulação" e nenhuma requisição externa é feita (Wikipédia removida).
- **CA-07** *Dado* um comentário técnico ("aqui usamos Spring"), *quando* processado pela análise incremental, *então* o prompt incremental não produz resposta técnica.

## Segurança e privacidade
- O conteúdo da entrevista e do currículo vai somente ao provedor de IA configurado pela organização.
- No Grok, `store:false` (já implementado).
- Logs registram ids, provedor, latência e tokens, sem a pergunta nem a resposta.

## Compatibilidade
- Os cards `QUESTION` existentes continuam exibidos. O botão "Responder com IA" continua.
- O resumo, as tarefas e as decisões continuam na análise incremental.
