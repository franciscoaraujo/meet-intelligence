# Design 005 — Detecção de perguntas e deduplicação

## Arquitetura proposta
Separar os dois trabalhos que hoje estão misturados no agregador:
1. **Perguntas (tempo real):** `QuestionDetectionService`, por segmento, local e barato.
2. **Análise da reunião (resumo, tarefas, decisões):** fluxo atual de blocos, com gatilho por volume.

```
segmento gravado (spec 002/003)
  ├─► QuestionDetectionService.onSegment(seg)            [executor serial por reunião]
  │      UtteranceAssembler  (continuidade por origem, RF-04)
  │      UtteranceClassifier (regras pontuadas, RF-02/03)
  │      EchoGuard           (RF-07)
  │      QuestionDeduplicator(RF-06) ─► InterviewQuestion (persistida)
  │                                       └─► InterviewCopilotService (spec 004)
  └─► TranscriptChunkAggregatorService (resumo/tarefas/decisões; gatilho por volume)
```

## Componentes
| Componente | Tipo | Responsabilidade |
|---|---|---|
| `UtteranceAssembler` | novo | Mantém o enunciado aberto por `(meetingId, audioSource)`; fecha por silêncio, por troca de origem ou por limite |
| `UtteranceClassifier` | novo, puro | Regras pontuadas; devolve `{type, score, signals[]}` (os sinais servem para explicar e testar) |
| `EchoGuard` | novo, puro | Similaridade MIC×TAB numa janela de 5 s |
| `QuestionDeduplicator` | novo | Jaccard sobre `TextRelevance` + janela de 120 s; decide criar, atualizar ou ignorar |
| `InterviewQuestion` (entidade) + repositório | novo | Tabela `interview_questions` (ver contratos) |
| `MeetingSerialExecutor` | novo | `ThreadPoolTaskExecutor` limitado + fila por `meetingId` (uma tarefa ativa por reunião) |
| `TranscriptChunkAggregatorService` | alterado | Remove `isImmediateTriggerSegment`; gatilho por volume/tempo; recebe só o delta das fusões |
| `AsyncConfig` | novo | Define o executor padrão limitado (substitui o `SimpleAsyncTaskExecutor`) |

## Classificador (regras iniciais, calibradas pelo corpus)
| Sinal | Peso |
|---|---|
| Termina com "?" | +0,45 |
| Começa com interrogativo (o que, qual, quais, como, por que, quando, onde, quem, você já, você conhece, já trabalhou) | +0,35 |
| Verbo de pedido (explique, descreva, conte, fale sobre, me fale, compare, detalhe, dê um exemplo, como você faria/trataria) | +0,45 (calibrado: era 0,40 e perdia as duas perguntas reais da reunião de campo) |
| Papel `REMOTE` com usuário `Candidato` | +0,15 |
| Conteúdo técnico (léxico de `TextRelevance` + termos do domínio) | +0,10 (só reforço) |
| Papel `SELF` com usuário `Candidato` | força `CANDIDATE_ANSWER` |
| Afirmação declarativa sem nenhum sinal acima | `CONTEXT_COMMENT` ou `TECHNICAL_DISCUSSION` |

A pontuação é limitada a 1,0. O tipo vem do sinal dominante: "?" ou interrogativo → `DIRECT_QUESTION`; verbo de pedido sem "?" → `INDIRECT_QUESTION`.

## Contratos
**Tabela `interview_questions` (migration V9):**
- Identificação e vínculo: `id`, `meeting_id`, `status`.
- Conteúdo: `question_text`, `normalized_fingerprint`, `utterance_type`, `score`.
- Origem: `source_segment_ids UUID[]`, `capture_start_ms`, `capture_end_ms`.
- Resposta: `answer_insight_id NULL`, `answer_version INT`.
- Tempo: `created_at`, `updated_at`.
- Índice: `(meeting_id, created_at)`.

**`ai_insights` (aditivo):** `question_id UUID NULL`, `chunk_id UUID NULL`, `source_segment_ids UUID[] NULL`.

**REST:**
- `GET /meetings/{id}/questions`.
- `POST /meetings/{id}/questions/{qid}/answer` (responder de novo).
- `POST /meetings/{id}/questions/{qid}/dismiss`.
- `POST /meetings/{id}/insights/trigger` passa a criar a pergunta pela RF-09, além da análise de bloco.

**WebSocket:** novo tópico `/topic/meetings/{id}/questions` (estado da pergunta). Os insights continuam em `/insights`.

## Tratamento de erros
- A detecção nunca bloqueia a gravação do segmento: exceção → log + métrica, e o segmento segue.
- `TranscriptChunk.chunkIndex` passa a ter índice único `(meeting_id, chunk_index)`, gerado sob lock por reunião, para evitar a corrida atual.
- Parse inválido do JSON do LLM **não** marca o bloco como processado (corrige P-13).

## Observabilidade
- Métricas:
  - `questions.detected{type}`, `questions.deduplicated`, `questions.echo_suppressed`.
  - `copilot.calls`.
  - `executor.queue.size{meeting}`, agregada.
- MDC: `meetingId`, `segmentId`, `questionId`.

## Decisões arquiteturais
| Decisão | Alternativas | Justificativa |
|---|---|---|
| Classificador local por regras pontuadas | LLM por segmento; modelo de ML treinado | Latência < 20 ms e custo zero; explicável e testável por corpus. O LLM confirma a pergunta dentro da chamada do copiloto (spec 004), sem custo extra. |
| Execução serial por reunião, em memória | RabbitMQ (já no compose) | Resolve a ordem e a concorrência numa instância sem nova peça. O RabbitMQ fica documentado como evolução P3 para múltiplas instâncias. |
| Dedup por Jaccard de radicais | Embeddings/pgvector | Reaproveita `TextRelevance` (já testado) e é síncrono e barato; embeddings dependem de chave da OpenAI |
| Tabela própria de perguntas | Reusar `ai_insights` | A pergunta tem ciclo de vida próprio (detectada → respondida → refeita); o insight passa a ser a resposta |

## Mudanças na implementação (2026-11-15)
| Design original | Implementado | Justificativa |
|---|---|---|
| Peso do verbo de pedido: +0,40 | +0,45 | Com 0,40, as duas perguntas reais da reunião de campo ("explique o conceito de sólido", "Explique sobre a residência do Rap me Kill") ficavam em 0,55 e não eram respondidas: o STT troca termos técnicos, então o reforço técnico não pontua. Resultado no corpus: 27/27 perguntas, 0/15 falsos positivos. |
| — | Sinal "dirigida ao candidato" (+0,10 para "você/sua/seu" em frase interrogativa ou pedido) | Perguntas comportamentais sem termo técnico ("como você lidaria com um conflito…") ficavam abaixo do limiar |
| — | Interrogativos falsos ("como eu disse…", "o que eu quero dizer…") não contam | Evitam falso positivo comum em fala natural |
| Gatilho manual dentro de `POST /insights/trigger` | Endpoint próprio `POST /meetings/{id}/questions/answer-latest` | "Consolidar com IA" (também chamado ao parar a gravação) não deve criar perguntas; são ações diferentes |
| Deduplicação por Jaccard dos radicais | Jaccard **sem palavras de enquadramento** ("explique", "pode", "de novo"…) + contenção ≥ 0,9; crescimento medido pelo conteúdo | "Explique o SOLID" e "Pode explicar o SOLID de novo?" só diferem no enquadramento; antes eram tratadas como perguntas diferentes |
| `closeDue` global | `closeDue` por reunião | Mantém a garantia de execução serial por reunião |
| `flushRemaining` refazia os últimos 10 segmentos quando não havia bloco pendente | Removido | Reanalisava conteúdo já analisado e duplicava insights (P-11) |
| — | IA e indexação do bloco disparadas só após o commit (`AfterCommit`) | A análise roda em outra thread e o insight referencia o bloco (FK `chunk_id`, V9) |
| Copiloto chamado pela interface | `InterviewCopilot` + `PendingInterviewCopilot` provisório | A implementação real é a spec 004 |
