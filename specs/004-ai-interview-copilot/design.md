# Design 004 — Copiloto de entrevistas

## Arquitetura proposta
```
QuestionDetectionService (spec 005) ──► InterviewCopilotService.answer(question)
     ├─ CopilotContextBuilder: últimos 8 segmentos + resumo + KnowledgeBaseService.buildContext(...)
     ├─ InterviewPromptFactory: carrega prompts/interview-answer.v1.md (system) + monta o user prompt
     ├─ AIOrchestrator.execute(request jsonMode, meeting, "INTERVIEW_ANSWER")   [existente]
     ├─ CopilotAnswerParser + CopilotAnswerValidator (schema + RN-01)
     └─ CopilotAnswerPublisher: cria/atualiza AIInsight(QUESTION, question_id) → WebSocket /insights
PromptManagerService (análise incremental) ── remove as diretivas de copiloto (RF-10)
```

## Componentes
| Componente | Tipo | Responsabilidade |
|---|---|---|
| `InterviewCopilotService` | novo | Orquestra a resposta; atualiza o status da `InterviewQuestion` |
| `CopilotContextBuilder` | novo | Contexto da conversa com papéis (spec 003) e a Base de Conhecimento |
| `InterviewPromptFactory` | novo | Prompts versionados em recurso; injeta `KNOWLEDGE_BASE_DIRECTIVES` (existente) |
| `CopilotAnswer` (record) | novo | Contrato RF-03 |
| `CopilotAnswerValidator` | novo | Valida o schema, os limites de palavras e as seções mínimas por tema (RF-04). **Heurística da RN-01:** termos técnicos e organizações citados em frases de primeira pessoa no passado ("trabalhei", "usei", "implementei") precisam aparecer no currículo; senão a resposta é rejeitada e há 1 nova tentativa com instrução corretiva. |
| `PromptManagerService` | alterado | Remove as diretivas de copiloto da análise incremental |
| `GeminiLlmProvider` (simulação) | alterado | Produz `CopilotAnswer` simulado a partir da KB e das respostas locais, com `simulated=true`; remove `fetchWikipediaSummaryPtBr` |
| Frontend `InterviewAnswerCard` | novo | `spoken_answer` em destaque, seções expansíveis, ações (RF-08) |

## Formato do prompt (resumo)
- **System:** papel de copiloto de entrevista; regras RN-01..RN-03; formato JSON exato; profundidade adaptativa; linguagem falada em primeira pessoa; proibição de inventar experiência; uso das seções por tema.
- **User:**
  - `<conversation>` com os segmentos rotulados (`[ENTREVISTADOR]`/`[CANDIDATO]`/`[DESCONHECIDO]`).
  - `<meeting_summary>`.
  - `<question type=... score=...>`.
  - `<knowledge_base>` (existente).

## Contratos
**Insight (JSON publicado):**
- Campos existentes, mais `questionId`, `answerVersion`, `simulated`.
- `metadata` (JSONB, já existe) guarda o `CopilotAnswer` completo.

**REST:** `POST /meetings/{id}/questions/{qid}/answer` (regenerar), definido na spec 005.

## Tratamento de erros
| Falha | Ação |
|---|---|
| `AIOrchestrator` lança exceção (todos os provedores falharam) | `FAILED` (`LLM_UNAVAILABLE`) |
| Parse/validação | 1 nova tentativa com mensagem corretiva; depois `FAILED` |
| Recusa (`stop_reason=refusal` no Claude; texto vazio nos demais) | `FAILED` (`REFUSAL`) |

Nenhuma falha do copiloto afeta a transcrição.

## Observabilidade
- Métricas:
  - `copilot.latency{provider,model}` (do fim da pergunta à publicação).
  - `copilot.outcome{status}`.
  - `copilot.validation.rejected{rule}`.
- O `LLMUsage` existente registra tokens e custo com `operationType=INTERVIEW_ANSWER`.

## Decisões arquiteturais
| Decisão | Alternativas | Justificativa |
|---|---|---|
| Chamada dedicada ao copiloto | Manter a resposta dentro da análise incremental | A análise incremental roda por volume e mistura tarefas; o copiloto precisa de gatilho por pergunta, prompt e contrato próprios |
| Confirmação de pergunta pelo LLM na mesma chamada | Chamada separada de classificação | Zero custo extra; corrige falsos positivos do detector local |
| Validador da RN-01 por heurística | Confiar só no prompt | O risco de inventar experiência é o mais grave para o candidato; a heurística é barata e testável. Limitação documentada: não pega toda paráfrase. |
| Prompts em arquivo versionado | Strings no código (atual) | Revisão e teste de contrato; evolução rastreável |
| Reaproveitar `AIOrchestrator` (BYOK, fallback, circuit breaker) | Novo cliente | Preserva o investimento existente |
