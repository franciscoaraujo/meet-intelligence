# Tasks 009 — Captura de áudio de qualquer fonte

**Status (2026-11-15):** T-01 a T-05 concluídas (frontend 84 testes, backend 146). Validação com WhatsApp, YouTube e tela inteira reais pendente (roteiro 006, seção 7).

Observação: o aviso de ruído (CA-03) é uma condição simples na `LiveMeetingPage` (`surface === 'monitor'`); o teste automatizado cobre a superfície informada pela captura e o indicador, não a renderização da página inteira.

| ID | Tarefa | Depende de | Concluída quando |
|---|---|---|---|
| T-01 | Opções do `getDisplayMedia`, superfície e ajuda por superfície em `audioSource.ts` | — | CA-01, CA-02, CA-04 verdes |
| T-02 | `SourceView.surface`, indicador e textos genéricos | T-01 | Teste do indicador com superfície verde |
| T-03 | Botão, aviso de ruído e aviso de consentimento na sala ao vivo | T-02 | CA-03 verde (aviso aparece com tela inteira) |
| T-04 | `REMOTE_LABEL` = "Participante remoto" + testes do backend | — | CA-05 verde; `mvn test` verde |
| T-05 | Roteiro de validação (itens de WhatsApp/YouTube/tela inteira) | T-01..T-04 | Seção no `validation-checklist.md` |
