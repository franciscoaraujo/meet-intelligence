# Spec 006 — Testes automatizados e de integração

Prioridade: **P0** (infraestrutura, pré-requisito de todas) · Status: **Aprovada (2026-11-15), não iniciada** · Problemas: P-16, P-19

## Contexto e problema
Faltam testes justamente nas áreas críticas: roteador de STT, fallback, agregador, deduplicação e captura. Um teste existente já falha (`TranscriptionServiceTest.shouldProcessAudioAndSaveSegments`). O frontend não tem executor de testes. Não há forma de validar o pipeline ponta a ponta sem uma reunião real.

## Objetivo
Uma suíte automatizada, executável localmente e em CI, que prova os critérios de aceite das specs 001-005 com áudio e provedores simulados, mais um roteiro de validação manual numa reunião real.

## Escopo
- Infraestrutura de testes do backend: JUnit 5/Mockito (existente), Testcontainers (PostgreSQL+pgvector), WireMock ou `MockRestServiceServer` para os provedores.
- Infraestrutura do frontend: Vitest + Testing Library + jsdom, com dublês de `MediaDevices`, `AudioContext`, `AudioWorklet` e `SpeechRecognition`.
- Fixtures de áudio WAV (silêncio, tom, fala sintética curta) e transcrições mockadas.
- Teste ponta a ponta no backend: `/chunk` → STT mockado → segmento → detecção → copiloto mockado → insight via STOMP.
- Observabilidade verificável (métricas existem e incrementam).
- Roteiro e relatório de validação manual.

## Fora de escopo
- Teste com reunião real automatizada.
- Testes de carga/performance (registrar como evolução).
- E2E de navegador real (Playwright): avaliar depois da spec 001.

## Requisitos funcionais
- **RF-01** O backend tem testes unitários para `TranscriptionChain`, cada provedor de STT, `SpeakerAttributionService`, `UtteranceClassifier` (corpus), `UtteranceAssembler`, `EchoGuard`, `QuestionDeduplicator`, `InterviewCopilotService` e `CopilotAnswerValidator`.
- **RF-02** Testes de timeout e falha de provedor com um servidor HTTP falso (atrasos, 401, 429, 500, corpo inválido).
- **RF-03** Teste de integração com Testcontainers: migrations V1..V9 aplicadas, idempotência por índice único e consultas de ordem.
- **RF-04** Teste ponta a ponta no backend (SpringBootTest + STOMP client): áudio da aba e áudio do microfone simulados; o provedor principal falha e o fallback é acionado; a transcrição segue para a detecção; a pergunta é respondida pelo copiloto mockado; o insight chega pelo WebSocket.
- **RF-05** O frontend tem testes de permissão (concedida/negada), início e fim do microfone, captura da aba (com e sem áudio), interrupção e recuperação, fila com retry idempotente, atualização em tempo real (WebSocket falso) e exibição de erros.
- **RF-06** O teste que já falha é corrigido ou reescrito na spec 002 (T-07); nenhum teste fica desativado sem justificativa registrada.
- **RF-07** Um comando único para cada lado: `mvn test` (backend) e `npm test` (frontend), documentados no README.
- **RF-08** Roteiro manual (`validation-checklist.md`) para reunião real e relatório (`validation-report.md`) que separa **validado com mocks** de **validado em reunião real**.

## Requisitos não funcionais
- **RNF-01** A suíte unitária do backend roda em < 60 s e a de integração em < 3 min (com Docker disponível).
- **RNF-02** Testes determinísticos: sem rede externa, sem relógio real (`Clock` injetável) e sem sleeps arbitrários (uso de Awaitility).
- **RNF-03** Os testes de integração são pulados com aviso se o Docker não estiver disponível (`@Testcontainers(disabledWithoutDocker = true)`).

## Regras de negócio
- **RN-01** Nenhuma funcionalidade é declarada validada em produção com base apenas em testes com mocks.

## Critérios de aceite
- **CA-01** *Dado* o repositório limpo com Docker, *quando* `mvn test` roda, *então* todos os testes passam, incluindo os de integração.
- **CA-02** *Dado* o frontend, *quando* `npm test` roda, *então* os testes de captura passam sem navegador real.
- **CA-03** *Dado* o teste ponta a ponta, *quando* o STT principal falha, *então* o cliente STOMP recebe o segmento do provedor de fallback e, em seguida, o insight da resposta.
- **CA-04** *Dado* um trecho repetido com o mesmo `clientSegmentId`, *quando* reenviado no teste ponta a ponta, *então* há um único segmento e um único insight.
- **CA-05** *Dado* o relatório final, *quando* consultado, *então* cada critério de aceite tem status e tipo de evidência (mock ou real).

## Segurança e privacidade
- As fixtures não contêm dados pessoais reais: o currículo de teste é fictício.
- Os testes não usam chaves reais. Testes opcionais com chave real ficam atrás de uma variável de ambiente e fora da CI.

## Compatibilidade
Os testes existentes continuam rodando (8 classes atuais).
