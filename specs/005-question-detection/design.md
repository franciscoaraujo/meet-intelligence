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
| Verbo de pedido (explique, descreva, conte, fale sobre, me fale, compare, detalhe, dê um exemplo, como você faria/trataria) | +0,40 |
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
