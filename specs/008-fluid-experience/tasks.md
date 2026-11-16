# Tasks 008 — Experiência fluida

**Status (2026-11-15):** T-01 a T-22 concluídas (testes automatizados: backend 146, frontend 78; `npm run check:bundle` verde). T-23 (medição com IA real) pendente. Desvios em [design.md](design.md#mudanças-na-implementação-2026-11-15).

A spec foi implementada em três fases, cada uma só depois da anterior verde.

## Fase A — Copiloto em streaming (P1)
| ID | Tarefa | Depende de | Concluída quando | Status |
|---|---|---|---|---|
| T-01 | `LlmStreamListener` + método `stream` padrão no `LLMProvider` | — | Provedores atuais compilam sem mudança; teste do caminho padrão (um delta) verde | ✅ `claudeWithoutKey` |
| T-02 | `SseClient` sobre `java.net.http.HttpClient` | — | Testes com servidor local lento e erro HTTP verdes | ✅ `LlmStreamingProvidersTest` |
| T-03 | Streaming no `GrokLlmProvider` | T-01, T-02 | Teste com eventos da Responses API verde | ✅ |
| T-04 | Streaming no `ClaudeLlmProvider` | T-01, T-02 | Teste com eventos da Messages API verde | ✅ |
| T-05 | Streaming no `GeminiLlmProvider` | T-01, T-02 | Teste com `streamGenerateContent?alt=sse` verde | ✅ |
| T-06 | `SpokenAnswerExtractor` | — | CA-05 e partições aleatórias (≥ 200 casos) verdes | ✅ `StreamingPrimitivesTest` (todas as divisões em 2 partes + 300 aleatórias) |
| T-07 | `DeltaThrottler` com relógio injetável | — | CA-06 verde | ✅ |
| T-08 | `AIOrchestrator.executeStreaming` com fallback antes do primeiro delta | T-01 | CA-03 e "falha após delta propaga" verdes | ✅ `AIOrchestratorStreamingTest` |
| T-09 | Prompt `interview-answer.v2.md` | — | Teste do prompt atualizado; v1 mantida | ✅ |
| T-10 | `CopilotStreamPublisher` + `InterviewCopilotService` em streaming | T-06..T-09 | CA-01, CA-02 e CA-04 verdes; testes da 004 verdes | ✅ `InterviewCopilotServiceTest` (14) |
| T-11 | `copilot-model` por provedor | — | Sem valor, mantém o modelo atual | ✅ |
| T-12 | Frontend: tópico `/copilot`, reducer, estados do card | T-10 | Testes do reducer e do card verdes | ✅ |
| T-13 | Streaming no teste ponta a ponta (STOMP real) | T-10 | Verde com Docker | ✅ `TranscriptionPipelineE2EIT` |

## Fase B — UI incremental e modo entrevista (P2)
| ID | Tarefa | Depende de | Concluída quando | Status |
|---|---|---|---|---|
| T-14 | `mergeById`/`upsertById` em polling, ressincronização e WebSocket | — | CA-07 verde | ✅ |
| T-15 | `useStickToBottom` + botão "N novas falas" | — | CA-08 verde | ✅ |
| T-16 | Esqueletos e transições com `prefers-reduced-motion` | — | Esqueleto inicial; `.mi-enter` sem animação com movimento reduzido | ✅ |
| T-17 | `InterviewModeLayout` + preferência salva + padrão por papel | T-12 | CA-09 verde | ✅ |
| T-18 | Extrair partes da `LiveMeetingPage` | T-14..T-17 | Testes verdes | ✅ `TranscriptItem` (memo), `InterviewModeLayout` |

## Fase C — Texto provisório e bundle (P3)
| ID | Tarefa | Depende de | Concluída quando | Status |
|---|---|---|---|---|
| T-19 | Texto provisório do microfone | — | CA-10 verde | ✅ (já existia; estilo ajustado e teste adicionado) |
| T-20 | "Participante remoto falando…" ligado ao VAD | — | Teste com dublê do VAD verde | ✅ `useCaptureSession.test` |
| T-21 | `React.lazy` por rota + `check-bundle-size.mjs` | — | CA-11 verde | ✅ |

## Fechamento
| ID | Tarefa | Depende de | Concluída quando | Status |
|---|---|---|---|---|
| T-22 | Atualizar o roteiro da 006 | Fases A-C | Roteiro revisado | ✅ seção 6 |
| T-24 | RF-20: microfone desligado no modo entrevista | T-17 | 2 testes em `useCaptureSession.test.ts` | ✅ |
| T-23 | Medição real (CA-12) e registro no `validation-report.md` | T-22, chave real de IA | Números reais p50/p90 no relatório | ⏳ pendente |
