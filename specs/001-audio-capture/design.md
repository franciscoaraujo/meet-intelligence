# Design 001 — Captura de áudio

## Arquitetura proposta
Extrair a captura de `LiveMeetingPage.tsx` (hoje com 1.414 linhas) para módulos sem dependência de React, testáveis isoladamente, e um hook fino que os conecta à tela.

```
src/features/capture/
  sessionClock.ts          relógio da sessão (performance.now() - t0)
  wavEncoder.ts            Float32 → WAV PCM16 mono (extraído de encodePcmToWavBase64)
  vadSegmenter.ts          VAD por RMS + histerese, pré-roll 300 ms, corte por silêncio 1,2 s / máx. 12 s
  pcm-capture.worklet.ts   AudioWorkletProcessor: envia frames de 20 ms ao main thread
  audioSource.ts           AudioSource {start, stop, state$, level$}: aquisição, eventos ended/mute/devicechange, recuperação
  segmentUploader.ts       fila por fonte, POST /chunk, retry idempotente, contadores
  webSpeechMicSource.ts    adaptação do fluxo Web Speech atual (modo alternativo)
  useCaptureSession.ts     hook React: orquestra as fontes e expõe o estado para a UI
components/capture/SourceStatusBadge.tsx  indicador por fonte
```

## Componentes e responsabilidades
| Componente | Responsabilidade | Testável com |
|---|---|---|
| `AudioSource` (kind `TAB` \| `MIC`) | Obter o stream (`getDisplayMedia`/`getUserMedia`), montar o grafo de áudio, emitir frames, gerenciar o estado RF-04 e a recuperação RF-06 | `navigator.mediaDevices` e `AudioContext` falsos |
| `VadSegmenter` | Produzir `AudioSegment {id, source, startMs, endMs, pcm}` a partir de frames | Arrays sintéticos (silêncio/seno) |
| `SegmentUploader` | Fila, envio, retry, interpretação de `status` da resposta, contadores de perda | `api` mockada |
| `WebSpeechMicSource` | Mesmo contrato de estado do `AudioSource`, produzindo texto via `/live-text` | `SpeechRecognition` falso |
| `useCaptureSession` | Ciclo de vida da sessão, relógio único, "Parar" com envio dos pendentes | Testing Library (`renderHook`) |

## Fluxo de dados
```
MediaStreamTrack ─► AudioWorklet (20 ms frames) ─► VadSegmenter ─► AudioSegment
   ─► wavEncoder ─► SegmentUploader ─► POST /chunk {clientSegmentId, audioSource, captureStartMs, captureEndMs}
   ◄─ {status, segments} ─► estado da fonte (último envio) + transcrição na tela
```

## Interfaces
```ts
type SourceKind = 'TAB' | 'MIC';
type SourceState = 'INATIVA' | 'SOLICITANDO_PERMISSAO' | 'ATIVA_FALANDO' | 'ATIVA_SILENCIO' | 'SEM_AUDIO'
  | 'PERMISSAO_NEGADA' | 'DISPOSITIVO_INDISPONIVEL' | 'INTERROMPIDA' | 'RECUPERANDO' | 'ERRO';
interface AudioSegment { id: string; source: SourceKind; startMs: number; endMs: number; pcm: Float32Array; sampleRate: 16000 }
interface UploadStats { sent: number; noSpeech: number; failed: number; retrying: number; dropped: number }
```
O contrato HTTP está definido na spec 002 (`ChunkTranscriptionResponse`).

## Eventos e mensagens
- O contrato WebSocket não muda.
- O frontend deduplica por `segment.id`, como hoje.
- Ao reconectar o WebSocket, uma única sincronização (`GET transcripts/insights`) substitui o polling fixo de 4,5 s: o polling passa a 30 s como rede de segurança (P-18).

## Tratamento de erros
| Origem | Mapeamento |
|---|---|
| `NotAllowedError` | `PERMISSAO_NEGADA` |
| `NotFoundError` / `OverconstrainedError` | `DISPOSITIVO_INDISPONIVEL` |
| `NotReadableError` (dispositivo em uso) | `ERRO` + mensagem |
| `AbortError` (usuário cancelou o compartilhamento) | `INATIVA` |
| Trilha `ended` | `INTERROMPIDA` → recuperação (microfone) ou botão (aba) |
| HTTP 5xx/rede | Retry (RF-08) |
| `status=FAILED` | Sem retry; exibe falha |

## Observabilidade
- Contadores em `UploadStats`, exibidos na UI e enviados no corpo do `/chunk` como `clientStats` (opcional), para o backend registrar a métrica `audio.segments.dropped{source}`.
- `console.debug` estruturado com `{meetingId, source, segmentId, event}`; sem áudio nem texto.

## Decisões arquiteturais
| Decisão | Alternativas | Justificativa |
|---|---|---|
| Microfone pelo pipeline do backend (padrão) | Manter a Web Speech como única opção | Corrige o P-02: o áudio capturado é o processado, com controle de eco e independente do navegador. Custo: chamadas de STT a mais (mitigado pelo VAD). A Web Speech fica como alternativa sem custo. |
| `echoCancellation: true` no microfone | `false` (atual) | O valor atual foi escolhido para o microfone "ouvir" o entrevistador; com a aba capturada separadamente isso só gera duplicidade |
| `AudioWorklet` | Manter `ScriptProcessorNode` | API recomendada; tira o processamento da thread de UI (P-05) |
| Segmentação no cliente (VAD) | Streaming contínuo | Mantém o endpoint HTTP atual e o custo baixo; streaming fica como evolução |
| Módulos fora da página | Manter no componente | Testabilidade (RNF-04) e redução da página de 1.414 linhas |
| Sem reabertura automática da aba | Reabrir sozinho | O navegador exige gesto do usuário para `getDisplayMedia` |
