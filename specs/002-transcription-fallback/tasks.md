# Tasks 002 — Fallback de transcrição

Pré-requisito: `006-T1` (infraestrutura de testes) e linha de base commitada.

**Status: implementada em 2026-11-15** — todas as tarefas concluídas. Evidência: 61 testes do backend verdes (incluindo `SpeechToTextRouterTest` 13, `SttProvidersTest` 10, `TranscriptionChainFactoryTest` 5, `SttSupportTest` 4, `TranscriptionServiceTest` 8 e `TranscriptionChunkIT` 4 com PostgreSQL real) e 5 testes do frontend. Validação em reunião real: **pendente** (006-T09).

| ID | Tarefa | Depende de | Concluída quando |
|---|---|---|---|
| T-01 | Criar `TranscriptionOutcome` (sealed) e `FailureReason` | — | Compila; teste unitário de `switch` exaustivo |
| T-02 | `HttpClientsConfig` com `RestTemplate` com timeout; injetar nos provedores de STT | — | Teste com servidor lento (MockRestServiceServer/WireMock) falha em ≤ timeout |
| T-03 | Remover a simulação dos provedores Deepgram e OpenAI; criar `SimulatedSttProvider` atrás de flag | T-01 | Teste: sem chave → provedor indisponível; erro HTTP → `Failed`; nenhum texto de "Carlos Silva" |
| T-04 | Extrair `GeminiAudioSttProvider` e `LocalGoogleSttProvider` (desligado por padrão, só `stdout`) | T-01, T-02 | Testes de sucesso, vazio e erro para cada um |
| T-05 | `TranscriptionChain` + `TranscriptionChainFactory` (ordem RF-03/RF-04, retry RNF-02, orçamento RNF-03) | T-03, T-04 | CA-01, CA-02, CA-03, CA-05 e CA-07 cobertos por testes unitários |
| T-06 | Migration V7 (colunas de segmento + índice único parcial) | — | Migration aplica num banco com dados reais sem erro; teste de integração (Testcontainers) |
| T-07 | `TranscriptionService.processAudioChunk`: idempotência, persistência só em `TRANSCRIBED`, checagem de organização; `SpeechToTextRouter` delega | T-05, T-06 | CA-04, CA-06; `TranscriptionServiceTest` corrigido e verde |
| T-08 | Controller: novo contrato de requisição/resposta; frontend consome `status` (mínimo: não quebra a tela) | T-07 | Teste de controller (MockMvc); typecheck do frontend; fluxo manual com o backend local |
| T-09 | Métricas e MDC; remover log de texto transcrito | T-05 | Teste verifica contadores; grep sem log de conteúdo |
| T-10 | Atualizar `diagnostico.md` (P-01/P-06/P-07 resolvidos) e README (flags novas) | T-01..T-09 | Docs revisadas |
