# Design 006 — Testes

## Estrutura
```
backend/src/test/java/com/meetingintelligence/
  unit/        speech/ (chain, providers), speaker/, question/, copilot/
  integration/ AbstractPostgresIT (Testcontainers pgvector/pgvector:pg16)
               TranscriptionPipelineE2EIT (SpringBootTest + WebSocketStompClient)
backend/src/test/resources/
  audio/ silence-1s.wav, tone-2s.wav, speech-short.wav (sintético, sem dado pessoal)
  question-corpus.csv
  fixtures/resume-ficticio.txt, llm/*.json (respostas mockadas válidas/inválidas)
frontend/src/test/
  fakes/ mediaDevices.ts, audioContext.ts, audioWorklet.ts, speechRecognition.ts, stompClient.ts
  setup.ts
frontend/src/features/capture/__tests__/*.test.ts
```

## Dependências novas (somente de teste)
| Lado | Dependência | Justificativa |
|---|---|---|
| Backend | `org.testcontainers:postgresql`, `junit-jupiter` | pgvector, índices parciais e `UUID[]` não funcionam em H2 |
| Backend | `org.wiremock:wiremock-standalone` (ou `MockRestServiceServer`, já no Spring) | Simular atrasos e códigos HTTP dos provedores; preferir `MockRestServiceServer` se bastar |
| Backend | `org.awaitility:awaitility` | Esperar eventos assíncronos sem `sleep` |
| Frontend | `vitest`, `@testing-library/react`, `@testing-library/user-event`, `jsdom` | Executor nativo do Vite (já usado no projeto) |

## Ponta a ponta no backend (RF-04)
1. `@SpringBootTest(webEnvironment=RANDOM_PORT)` + Postgres do Testcontainers.
2. Beans de provedor de STT e de `LLMProvider` substituídos por fakes programáveis (`@TestConfiguration`), sem rede.
3. O cliente STOMP assina `/topic/meetings/{id}/{transcripts,questions,insights}`.
4. `POST /chunk` com `audio/tab-question.wav` (o fake devolve "Explique o conceito de SOLID") → o fake principal lança erro → o fake secundário transcreve.
5. Awaitility espera: o segmento (`provider` = secundário, `audioSource=TAB`, `speakerRole=REMOTE`), depois a pergunta `DETECTED`, depois o insight `QUESTION` com `questionId`.
6. Repete o envio com o mesmo `clientSegmentId` → as contagens não mudam.
7. `POST /chunk` com `audioSource=MIC` e fala do candidato → `CANDIDATE_ANSWER`, sem copiloto.

## Dublês do frontend
- `fakeMediaDevices`:
  - `getUserMedia`/`getDisplayMedia` configuráveis (conceder, negar com `NotAllowedError`, sem trilha de áudio).
  - Trilhas falsas com `stop()`, `readyState` e `dispatchEvent('ended')`.
- `fakeAudioContext` + `fakeAudioWorkletNode`: o teste injeta frames PCM e verifica `close()`.
- `fakeSpeechRecognition`: emite `result`, `error` e `end`.
- `api` mockada (vi.mock) com respostas `TRANSCRIBED`, `NO_SPEECH`, `FAILED` e erro de rede.

## Observabilidade nos testes
`SimpleMeterRegistry` injetado; as asserções verificam os contadores `stt.requests`, `questions.deduplicated` e `copilot.outcome`.

## Validação manual (reunião real)
`validation-checklist.md` cobre:
- Chrome atual, com e sem fone.
- Permissão negada e concedida.
- Encerramento do compartilhamento.
- Queda de rede (DevTools offline por 10 s).
- Chave inválida e válida.
- Pergunta direta, indireta e comentário técnico.

O resultado vai para `validation-report.md` com data, navegador, provedores e observações.

## Decisões
| Decisão | Alternativa | Justificativa |
|---|---|---|
| Testcontainers | H2 | O H2 não suporta os tipos e índices usados (pgvector, `UUID[]`, índice parcial) |
| Vitest | Jest | Integra nativamente com o Vite 8 já usado; sem configuração de transpile extra |
| Fakes programáveis em vez de chamadas reais | Testes contra APIs reais | Determinismo, custo zero, nenhuma chave na CI; a validação real fica no roteiro manual |
