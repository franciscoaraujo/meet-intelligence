# Design 002 — Fallback de transcrição

## Arquitetura proposta
Manter `TranscriptionService` como fachada de aplicação e `SpeechToTextProvider` como porta. A orquestração da cadeia sai do `TranscriptionService` (hoje com ~180 linhas de HTTP inline) para um componente dedicado e testável.

```
TranscriptController ──► TranscriptionService.processAudioChunk(cmd)
                              │ 1. idempotência (clientSegmentId)
                              │ 2. TranscriptionChain.transcribe(audio, ctx)
                              │ 3. persistência + publicação (só se TRANSCRIBED)
                              ▼
                     TranscriptionChain (novo)
                      ├─ TranscriptionChainFactory: monta a lista a partir de OrganizationAiConfig + chaves globais
                      ├─ para cada SpeechToTextProvider disponível:
                      │     ResilientCall (timeout + 1 retry p/ 429/5xx/timeout)
                      │     outcome = provider.transcribe(...)
                      │     TRANSCRIBED | NO_SPEECH → para; FAILED → próximo
                      └─ ChainResult(status, segments, provider, attempts[])
```

## Componentes e responsabilidades
| Componente | Tipo | Responsabilidade |
|---|---|---|
| `TranscriptionOutcome` | novo (domain/speech) | `sealed interface` com `Transcribed(List<TranscriptionSegmentDto>, provider)`, `NoSpeech(provider)` e `Failed(provider, FailureReason, retryable)` |
| `SpeechToTextProvider` | alterado | Novo método `TranscriptionOutcome transcribe(AudioInput, SttContext)`. O antigo `transcribeAudio` vira adaptador `@Deprecated` até a migração terminar. |
| `GeminiAudioSttProvider` | novo (extraído) | Código Gemini hoje em `TranscriptionService:118-158`; recebe a chave da organização pelo contexto |
| `OpenAiTranscribeSttProvider` | renomeado de `OpenAiWhisperSpeechToTextProvider` | Aceita chave da organização ou global |
| `DeepgramSpeechToTextProvider` | alterado | Sem simulação; erro → `Failed` |
| `LocalGoogleSttProvider` | novo (extraído) | Código Python hoje em `TranscriptionService:228-265`; `@ConditionalOnProperty` desligado por padrão; lê só `stdout` |
| `SimulatedSttProvider` | novo | Simulação; existe só com `app.speech.simulation-enabled=true` |
| `TranscriptionChain` / `TranscriptionChainFactory` | novos | Ordem da cadeia (RF-03/RF-04), orçamento de tempo, métricas |
| `SpeechToTextRouter` | substituído | Vira fino delegador para `TranscriptionChain` (preserva o bean; nenhuma remoção de funcionalidade) |
| `HttpClientsConfig` | novo | `RestTemplate` com timeouts (RNF-01), injetado nos provedores no lugar de `new RestTemplate()` |

## Fluxo de dados
1. O controller valida o tamanho (base64 ≤ 12 MB) e decodifica. Base64 inválido → 400.
2. `TranscriptionService` verifica a reunião **da organização do usuário** e procura `(meeting_id, client_segment_id)`. Se existir, devolve o resultado anterior.
3. `TranscriptionChain` executa. Trecho com duração < 0,3 s (calculada pelo cabeçalho WAV) → `NO_SPEECH` direto.
4. Com `Transcribed`, grava os segmentos com `source=provider`, `audio_source`, `client_segment_id`, `capture_start_ms`/`capture_end_ms` (spec 001/003). Depois aciona o agregador e publica no WebSocket.
5. Responde `ChunkTranscriptionResponse`.

## Interfaces e contratos
**Requisição** `POST /api/v1/meetings/{id}/transcripts/chunk`
```json
{
  "base64Audio": "...", "mimeType": "audio/wav",
  "clientSegmentId": "uuid-v4",          // opcional na transição; obrigatório após a spec 001
  "audioSource": "TAB",                   // TAB | MIC (opcional; padrão TAB)
  "captureStartMs": 125000, "captureEndMs": 131400  // relógio da sessão de captura (spec 001)
}
```
**Resposta 200**
```json
{
  "status": "TRANSCRIBED",               // TRANSCRIBED | NO_SPEECH | FAILED
  "provider": "DEEPGRAM",
  "segments": [ { "...TranscriptSegment" } ],
  "attempts": [ { "provider": "GEMINI", "outcome": "FAILED", "reason": "HTTP_5XX", "latencyMs": 812 } ],
  "duplicate": false
}
```
`reason` ∈ `NO_PROVIDER_CONFIGURED, TIMEOUT, TIMEOUT_BUDGET, HTTP_4XX, HTTP_429, HTTP_5XX, INVALID_AUDIO, PARSE_ERROR, NETWORK`.

**Banco (migration V7, aditiva)** em `transcript_segments`:
- `client_segment_id UUID NULL`, com índice único parcial `(meeting_id, client_segment_id) WHERE client_segment_id IS NOT NULL`.
- `audio_source VARCHAR(10) NULL` (`TAB|MIC|UPLOAD|MANUAL`).
- `capture_start_ms BIGINT NULL` e `capture_end_ms BIGINT NULL`.

## Eventos e mensagens
Inalterado: `/topic/meetings/{id}/transcripts` recebe cada segmento gravado. `NO_SPEECH`/`FAILED` não publicam segmento; a falha é devolvida ao cliente pela resposta HTTP (a spec 001 exibe o status da fonte).

## Tratamento de erros
- Exceções de provedor viram `Failed` dentro do provedor. A cadeia nunca propaga exceção de provedor.
- Exceções de infraestrutura (banco) → HTTP 500 e o cliente reenvia com o mesmo `clientSegmentId`.
- `INVALID_AUDIO` interrompe a cadeia: os outros provedores também falhariam.

## Observabilidade
- Contexto de log em MDC: `meetingId`, `clientSegmentId`, `audioSource`, `provider`.
- Log `INFO` por tentativa: provedor, resultado, latência e bytes. Nunca o texto. **Remover** o log que imprime o texto transcrito (`TranscriptionService:214`).
- Métricas: `stt.requests{provider,outcome}` (counter), `stt.latency{provider}` (timer), `stt.chain.exhausted` (counter) e `stt.duplicates` (counter).

## Decisões arquiteturais
| Decisão | Alternativas | Justificativa |
|---|---|---|
| `sealed interface TranscriptionOutcome` | Exceções; `Optional`; lista vazia | Torna impossível confundir "sem fala" com "falha" (causa do P-01); Java 21 permite `switch` exaustivo |
| Retry/timeout com Resilience4j (já é dependência) | Spring Retry; código manual | Sem nova dependência; padrão já usado no `AIOrchestrator` |
| Idempotência por índice único no PostgreSQL | Redis | Garantia transacional sem nova peça; o Redis existe, mas não é necessário aqui |
| Simulação por flag | Remover a simulação | Preserva a experiência de desenvolvimento sem chaves, sem risco em produção |
| Manter `SpeechToTextRouter` como delegador | Apagar | Não remove a funcionalidade nem quebra injeções existentes |

## Mudanças na implementação (2026-11-15)
Ajustes feitos durante a implementação, com a justificativa de cada um:

| Design original | Implementado | Justificativa |
|---|---|---|
| Coluna `client_segment_id` + índice único parcial em `transcript_segments` | Tabela `transcript_ingestions` com `UNIQUE (meeting_id, client_segment_id)` (V7) | Um trecho pode ser **fundido** a um segmento anterior (fusão de falas), então o id do trecho não mapeia 1:1 para um segmento. A tabela registra o resultado de cada trecho (inclusive `NO_SPEECH`/`FAILED`) e permite reprocessar só os que falharam. |
| `TranscriptionChain` novo + `SpeechToTextRouter` delegador | `SpeechToTextRouter` passou a executar a cadeia; `TranscriptionChainFactory` monta a ordem | Evita uma classe que só repassaria chamadas; o nome já conhecido no código foi mantido |
| Antiga interface `SpeechToTextProvider` mantida como `@Deprecated` | Removida, junto com `TranscriptionResult` | Sem nenhum uso restante; a simulação embutida (causa do P-06) saiu junto. Nenhuma funcionalidade foi removida. |
| Resposta com status `TRANSCRIBED / NO_SPEECH / FAILED` | Também `PROCESSING` | Mesmo trecho chegando em paralelo (retry concorrente): o primeiro processamento entrega o resultado pelo WebSocket |
| `Resilience4j` para retry/timeout | Timeout no `RestTemplate` (`SttHttpClients`) + retry e orçamento no roteador | Regras simples (1 retry só para falhas transitórias + orçamento total), testáveis com relógio controlado; sem configuração adicional |
| Chave do Gemini na URL (`?key=`) | Cabeçalho `x-goog-api-key` | Evita a chave em logs de requisição |
| O texto repetido nos últimos 3 segmentos seria tratado só pela spec 005 | Mantido no `/chunk` (`duplicate=true`, nada gravado) | Comportamento existente preservado até a spec 005 substituí-lo |
