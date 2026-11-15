# Tasks 005 — Detecção de perguntas e deduplicação

Pré-requisitos: spec 003 `T-03` (papel do falante nos segmentos).

| ID | Tarefa | Depende de | Concluída quando |
|---|---|---|---|
| T-01 | Corpus de teste (≥ 40 frases rotuladas em pt-BR, incluindo as da reunião real e as transcrições com erros de STT) em `src/test/resources/question-corpus.csv` | — | Corpus revisado pelo usuário (contém o vocabulário real das entrevistas) |
| T-02 | `UtteranceClassifier` (puro) + teste de corpus | T-01 | CA-02, CA-03, CA-06 (metas da RNF-02) |
| T-03 | `UtteranceAssembler` (continuidade por origem, fechamento) | 003-T03 | CA-01; testes de silêncio, troca de origem e limite de 40 palavras |
| T-04 | `EchoGuard` | 003-T03 | CA-05 |
| T-05 | Migration V9 + entidade/repositório `InterviewQuestion`; colunas novas em `ai_insights` | — | Migration verde em Testcontainers |
| T-06 | `QuestionDeduplicator` | T-05 | CA-04; testes de janela temporal e de crescimento ≥ 30 % |
| T-07 | `AsyncConfig` (executor limitado) + `MeetingSerialExecutor` | — | CA-08 com teste concorrente (3 segmentos simultâneos) |
| T-08 | `QuestionDetectionService` ligando T-02..T-07; chamada ao copiloto (interface da spec 004, inicialmente stub) | T-02..T-07 | CA-07; teste de integração do serviço |
| T-09 | Agregador: remover `isImmediateTriggerSegment`, gatilho por volume, fusão envia só o delta, índice único de `chunk_index`, não marcar bloco como processado em parse inválido | T-07 | Testes do agregador; regressão da reunião real (duas perguntas separadas) |
| T-10 | REST + tópico WebSocket `/questions`; gatilho manual (RF-09) | T-08 | Testes de controller e de WebSocket (spec 006) |
| T-11 | Atualizar a documentação e o diagnóstico (P-10..P-13, P-15) | T-01..T-10 | Docs revisadas |
