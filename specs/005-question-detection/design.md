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

## Correção após uso real (2026-11-15, 22:05)
Na primeira entrevista real, duas perguntas não viraram card (e outras duas só passaram pelo botão manual): o entrevistador fazia um comentário ou anúncio antes da pergunta ("muito bom, ficou completo... última pergunta dessa rodada: como você usaria...", "a pergunta é como você usaria...") e a transcrição não põe "?". O classificador só reconhecia o interrogativo no começo da fala.

| Mudança | Detalhe |
|---|---|
| Pergunta no meio da fala | `EMBEDDED_QUESTION`: interrogativo + "você" + verbo no condicional (usaria, lidaria, imaginaria, escolheria) a até 3 palavras, em qualquer posição. Sinal "pergunta no meio da fala" |
| Falsas perguntas | `FALSE_INTERROGATIVE` passa a cobrir "como você pode ver / viu / mencionou / disse / falou / sabe" (eram falsos positivos não cobertos pelo corpus) |
| Corpus | +11 frases: 4 perguntas reais da entrevista, 1 comentário real, 2 variações e 4 armadilhas. Resultado: 33/33 perguntas (100%), 0/20 falsos positivos (antes: 27/27 e 0/15 num corpus sem esse padrão) |
| Limitação | Perguntas no meio da fala sem verbo no condicional ("o que você achou de...", "qual foi o maior desafio") ainda dependem do começo da fala ou do botão "Responder última pergunta" |

## Botão "Pergunta terminou" (RF-09 revisto em 2026-11-15, 22:15)
Uso real: "Responder última pergunta" juntava as 3 últimas falas remotas (no caso, um comentário + o começo da pergunta seguinte), e o modelo respondia "não era pergunta"; a pergunta era descartada e o card sumia. Os 8 cliques da sessão falharam assim.

| Mudança | Detalhe |
|---|---|
| Significado do botão | Renomeado para **"Pergunta terminou"**: o candidato sinaliza que o entrevistador acabou de perguntar. No modo entrevista, também aparece ao lado de "Pergunta atual" |
| Texto enviado | Toda a fala remota (sem eco) desde a última pergunta não descartada, limitada a 120 s e 10 trechos, mais o enunciado em aberto. Cliques descartados não contam como limite |
| Tipo | `UtteranceType.MANUAL_TRIGGER` (coluna VARCHAR, sem migration) |
| Copiloto | O prompt recebe `<manual_trigger>`: o trecho pode ter comentários antes; o modelo identifica a última pergunta, devolve-a em `question` e responde. Se disser que não é pergunta, nova tentativa com correção; se insistir, a pergunta fica `FAILED` (com "Tentar novamente"), nunca `DISMISSED` |
| Título do card | Passa a ser a pergunta entendida pelo modelo (`question`), não o trecho inteiro |
| Retorno visual | "Enviando pergunta…" até a pergunta chegar pelo WebSocket (máx. 8 s); cliques repetidos ignorados enquanto isso |
| Concorrência | Pergunta já em `ANSWERING` não dispara uma segunda geração (vale também para a deduplicação automática) |
| Testes | `QuestionDetectionServiceTest` (+3), `InterviewCopilotServiceTest` (+2), `live.test.tsx` |

