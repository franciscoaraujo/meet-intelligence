# Tasks 011 — Idioma da entrevista

**Status (2026-11-15):** T-01 a T-07 concluídas. Backend 163 testes, frontend 97. Corpus: PT 33/33 e 0/20, EN 16/16 e 0/12, FR 15/15 e 0/10. Os corpus EN/FR foram escritos pelo agente: precisam de frases de entrevistas reais.

| ID | Tarefa | Depende de | Concluída quando |
|---|---|---|---|
| T-01 | `InterviewLanguage` + endpoint `PATCH /language` | — | CA-01 verde |
| T-02 | Prompt de transcrição do Gemini por idioma | T-01 | CA-02 verde |
| T-03 | Classificador por idioma + corpus EN/FR + idioma no `SegmentEvent` | T-01 | CA-03 verde; corpus PT inalterado |
| T-04 | `<answer_language>` no copiloto | T-01 | CA-04 verde |
| T-05 | Validador EN/FR | — | CA-05 verde |
| T-06 | Frontend: seletor, preferência, Nova captura, Web Speech | T-01 | CA-06 verde |
| T-07 | Roteiro de validação (entrevista em inglês e em francês) | T-01..T-06 | Seção no checklist |
