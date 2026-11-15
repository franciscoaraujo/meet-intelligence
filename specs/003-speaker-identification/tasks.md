# Tasks 003 — Identificação de falantes

Pré-requisitos: spec 002 `T-06`/`T-07` (colunas e contrato), spec 001 `T-07` (envio de `audioSource`).

**Status (2026-11-15):** T-01 a T-06 concluídas. Evidência: `SpeakerAttributionServiceTest` 8, `TranscriptionServiceTest` 8, `TranscriptionChunkIT` 6 (PostgreSQL real, inclui microfone→SELF e texto digitado→MANUAL), `speaker.test.ts` 4. Backend 69 testes verdes; frontend 47. Validação em reunião real: pendente.

| ID | Tarefa | Depende de | Concluída quando |
|---|---|---|---|
| T-01 | Enums `AudioSource` e `SpeakerRole`; migration V8; campos na entidade | 002-T06 | Migration aplicada com dados existentes; teste de mapeamento JPA |
| T-02 | `SpeakerAttributionService` com as regras RF-03 | T-01 | Testes unitários para cada linha da tabela RF-03 e para a confiança < 0,5 |
| T-03 | Usar o serviço em `/chunk`, `/live-text` e upload; remover `existingParticipants.get(0)` e `source("WEB_SPEECH_LIVE")` fixo | T-02, 002-T07 | CA-01, CA-02, CA-04, CA-05 |
| T-04 | `PATCH` de rótulo (RF-09) e de `userInterviewRole` | T-01 | CA-06; teste de controller |
| T-05 | Frontend: remover `resolveActiveSpeaker`; ícone de origem, rótulo, confiança; horário pela `capture_start_ms` | T-03, 001-T07 | CA-03 em teste de componente; typecheck |
| T-06 | Atualizar a documentação e o diagnóstico (P-08/P-09) | T-01..T-05 | Docs revisadas |
