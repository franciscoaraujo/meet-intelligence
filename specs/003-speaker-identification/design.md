# Design 003 — Identificação de falantes

## Arquitetura proposta
A atribuição sai do frontend (heurística) e do `processLiveTextSegment` (participante fixo) para um único componente de domínio, chamado por todos os caminhos de gravação de segmento.

```
TranscriptionService (chunk | live-text | upload)
   └─► SpeakerAttributionService.attribute(meeting, user, audioSource, providerSegmentDto?)
          → SpeakerAttribution { role, label, confidence, participant?, speakerTag? }
```

## Componentes
| Componente | Tipo | Responsabilidade |
|---|---|---|
| `AudioSource` (enum) | novo, domínio | `TAB, MIC, UPLOAD, MANUAL` |
| `SpeakerRole` (enum) | novo, domínio | `SELF, REMOTE, UNKNOWN` |
| `SpeakerAttributionService` | novo, aplicação | Regras RF-03; cria/obtém o participante do usuário ou o "remoto N" |
| `TranscriptSegment` | alterado | Campos `audioSource`, `speakerRole`, `speakerConfidence`, `captureStartMs`, `captureEndMs` |
| `Meeting` | alterado | `userInterviewRole` (`CANDIDATE`/`INTERVIEWER`, opcional) |
| `TranscriptionService.findOrCreateParticipant` | substituído | Lógica movida para `SpeakerAttributionService` |
| Frontend | alterado | Remove `resolveActiveSpeaker`; mostra o rótulo, um ícone de origem (aba/microfone) e a confiança baixa em cinza |

## Fluxo de dados
O segmento é transcrito (spec 002) com `audioSource` vindo da requisição. Para cada `TranscriptionSegmentDto`, `attribute()` devolve o papel e o rótulo, e o segmento é persistido com esses dados.

## Contratos
- `POST /live-text` ganha `audioSource` (opcional, padrão `MANUAL`) e `clientSegmentId` (opcional, idempotência).
- `PATCH /transcripts/{segmentId}` aceita `speakerLabel`, além de `correctedText` (RF-09).
- `PATCH /meetings/{id}` aceita `userInterviewRole`.
- JSON do segmento ganha `audioSource`, `speakerRole`, `speakerConfidence`, `captureStartMs` e `captureEndMs`.

**Migration (V8, aditiva):**
- `transcript_segments`: `speaker_role VARCHAR(10) NULL` e `speaker_confidence NUMERIC(4,3) NULL`. `audio_source` e as colunas `capture_*` vêm da V7 (spec 002).
- `meetings`: `user_interview_role VARCHAR(20) NULL`.

## Eventos
O tópico `/topic/meetings/{id}/transcripts` passa a carregar os campos novos.

## Tratamento de erros
Atribuição nunca lança exceção para o fluxo de transcrição. Na dúvida, devolve `UNKNOWN` com confiança 0.

## Observabilidade
Métrica `segments.attributed{audioSource,role}`. Log `DEBUG` com o papel decidido e o motivo (regra aplicada), sem texto.

## Decisões arquiteturais
| Decisão | Alternativas | Justificativa |
|---|---|---|
| Origem do áudio como evidência primária | Heurística de atividade (atual); diarização por IA em tudo | É a evidência mais forte disponível, com custo zero; atende "não afirmar sem evidência" |
| Diarização só quando o provedor já a fornece | Implementar diarização própria agora | A spec pede viabilidade demonstrada; com fontes separadas, o caso principal (candidato × entrevistador) já se resolve |
| Papel do usuário configurável por reunião | Fixar "candidato" | O produto também atende reuniões gerais e o lado do entrevistador |
