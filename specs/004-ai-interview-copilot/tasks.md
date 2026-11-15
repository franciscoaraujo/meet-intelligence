# Tasks 004 — Copiloto de entrevistas

Pré-requisitos: spec 005 `T-08` (perguntas detectadas e interface do copiloto).

| ID | Tarefa | Depende de | Concluída quando |
|---|---|---|---|
| T-01 | `CopilotAnswer` (record) + `CopilotAnswerParser` (tolerante a cercas markdown) | — | Testes com JSON válido, inválido e com cercas |
| T-02 | `CopilotAnswerValidator` (schema, limites, seções por tema, heurística RN-01) | T-01 | CA-01, CA-02 com respostas mockadas que violam e que atendem às regras |
| T-03 | `prompts/interview-answer.v1.md` + `InterviewPromptFactory` + teste de contrato (placeholders, regras presentes) | — | Teste garante que o prompt contém as regras RN-01..RN-03 e o schema |
| T-04 | `CopilotContextBuilder` (8 segmentos com papéis, resumo, KB) | 003-T03 | Teste com segmentos de papéis mistos |
| T-05 | `InterviewCopilotService` (orquestração, retry corretivo, status da pergunta, publicação/atualização do card) | T-01..T-04, 005-T08 | CA-03, CA-04, CA-05 com `AIOrchestrator` mockado |
| T-06 | Remover as diretivas de copiloto do `PromptManagerService` (RF-10) | T-05 | CA-07 (teste do prompt incremental) |
| T-07 | Simulação: `CopilotAnswer` simulado a partir da KB; remover a consulta à Wikipédia | T-01 | CA-06 (teste garante zero chamada HTTP externa) |
| T-08 | Frontend `InterviewAnswerCard` + ações (gerar de novo, não era pergunta, copiar) | T-05, 005-T10 | Testes de componente; typecheck |
| T-09 | Medição de latência real (RNF-01) numa sessão com chave real | T-08 | Números registrados em `006/validation-report.md`, marcados como medição real |
| T-10 | Atualizar a documentação e o diagnóstico (P-14) | T-01..T-09 | Docs revisadas |
