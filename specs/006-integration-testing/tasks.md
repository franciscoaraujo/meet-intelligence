# Tasks 006 — Testes

**Status (2026-11-15):** T-01 a T-08 concluídas (`MigrationsIT`, `TranscriptionChunkIT`, `TranscriptionPipelineE2EIT`, dublês de mídia no frontend, `validation-checklist.md`). T-09 (reunião real) pendente — ver `validation-report.md`.

| ID | Tarefa | Depende de | Concluída quando |
|---|---|---|---|
| T-01 | Infraestrutura: Vitest + Testing Library + `setup.ts` no frontend; Testcontainers + Awaitility no backend; `npm test`/`mvn test` documentados | Aprovação das dependências | Um teste trivial de cada lado passa; o teste de integração é pulado com aviso sem Docker |
| T-02 | Corrigir `TranscriptionServiceTest` (hoje falhando) ou reescrevê-lo junto com 002-T07 | T-01 | Suíte do backend 100 % verde |
| T-03 | Fixtures de áudio sintético e currículo fictício | T-01 | Arquivos versionados, < 200 KB no total |
| T-04 | Dublês do frontend (`mediaDevices`, `AudioContext`/Worklet, `SpeechRecognition`, STOMP) | T-01 | Usados pelos testes da spec 001 |
| T-05 | `AbstractPostgresIT` + teste das migrations V1..V9 | T-01, 002-T06, 003-T01, 005-T05 | Migrations aplicam do zero e com dados |
| T-06 | Fakes programáveis de provedores de STT/LLM (`@TestConfiguration`) | T-01 | Reutilizados por 002, 004 e 005 |
| T-07 | `TranscriptionPipelineE2EIT` (RF-04, CA-03, CA-04) | T-05, T-06, 004-T05, 005-T10 | Verde localmente com Docker |
| T-08 | `validation-checklist.md` | — | Revisado pelo usuário |
| T-09 | Execução do roteiro em reunião real + `validation-report.md` | Todas as specs | Relatório com evidência real × mock por critério |
