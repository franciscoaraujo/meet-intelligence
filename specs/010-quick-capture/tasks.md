# Tasks 010 — Captura avulsa

**Status (2026-11-15):** T-01 a T-05 concluídas. Testes: `quickCapture.test.tsx` (CA-01..CA-04), `MeetingServiceQuickCaptureTest` (CA-05). Frontend 90, backend 150.

| ID | Tarefa | Depende de | Concluída quando |
|---|---|---|---|
| T-01 | `quickCapture.ts` + `QuickCaptureButton` | — | CA-01, CA-02 verdes |
| T-02 | Botão no menu lateral e no Dashboard; item de menu renomeado | T-01 | Visível nas duas telas |
| T-03 | Link opcional no modal | — | CA-03 verde |
| T-04 | "Captura avulsa" nas listas | T-01 | CA-04 verde |
| T-05 | Backend: link em branco → `null` | — | CA-05 verde; `mvn test` verde |
