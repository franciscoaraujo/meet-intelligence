# Relatório de validação (spec 006)

Data: 2026-11-15 · Branch: `feat/knowledge-base`

> Regra (RN-01 da spec 006): nada aqui está validado em produção. Tudo abaixo foi verificado com **dublês, mocks e banco PostgreSQL real em container**. A validação em reunião real segue o [roteiro](validation-checklist.md) e ainda **não foi executada**.

## Suítes
| Suíte | Comando | Resultado |
|---|---|---|
| Backend (unitários + integração) | `mvn test` (JDK 21; Docker para os `*IT`) | **167 testes, 0 falhas** (118 antes da spec 008) |
| Uso real (2026-11-15, 21:37-22:01) | Entrevista com captura da aba, STT local, Grok | Copiloto com `grok-4.7`: primeira palavra em 32-61 s; com `grok-4.20-0309-non-reasoning`: 0,8-1,0 s (resposta completa 5,4-6,4 s). 2 perguntas não detectadas → classificador corrigido |
| Frontend | `npm test` | **101 testes, 0 falhas** (53 antes da spec 008) |
| Typecheck / build do frontend | `npx tsc -b`, `npm run build`, `npm run check:bundle` | Sem erros; maior chunk 335 KB, sem aviso do Vite |

Correção de registro: até o commit da spec 004, os totais "completos" do backend (61, 69, 93, 108) **não incluíam** as classes `*IT`, porque o Surefire as exclui por padrão. Elas foram executadas separadamente a cada etapa e passaram. O `pom.xml` agora inclui `*IT` no `mvn test`.

## Evidência por critério de aceite
Legenda: **A** = teste automatizado com mocks/dublês; **I** = teste de integração com PostgreSQL real e contexto Spring; **R** = reunião real (pendente).

| Spec | Critério | Evidência | Tipo |
|---|---|---|---|
| 002 | CA-01 fallback ao falhar o principal | `SpeechToTextRouterTest`, `TranscriptionChunkIT`, `TranscriptionPipelineE2EIT` | A, I |
| 002 | CA-02 todos falham → FAILED, nada gravado | `SpeechToTextRouterTest`, `TranscriptionServiceTest`, `TranscriptionChunkIT` | A, I |
| 002 | CA-03 sem fala encerra a cadeia | `SpeechToTextRouterTest`, `TranscriptionServiceTest` | A |
| 002 | CA-04 sem chave → sem transcrição inventada | `SttProvidersTest`, `TranscriptionChainFactoryTest` | A |
| 002 | CA-05 timeout | `SttSupportTest` (servidor lento real), `SttProvidersTest` | A |
| 002 | CA-06 idempotência | `TranscriptionServiceTest`, `TranscriptionChunkIT`, `TranscriptionPipelineE2EIT` | A, I |
| 002 | CA-07 sem retry em 4xx | `SpeechToTextRouterTest`, `SttProvidersTest` | A |
| 001 | CA-01..CA-08 | `audioSource`, `useCaptureSession`, `segmentUploader`, `vadSegmenter`, `webSpeechMicSource`, `SourceStatusBadge` (dublês de mídia) | A |
| 003 | CA-01..CA-06 | `SpeakerAttributionServiceTest`, `TranscriptionChunkIT`, `speaker.test.ts` | A, I |
| 005 | CA-01..CA-05, CA-07, CA-08 | `QuestionDetectionServiceTest` (inclui concorrência com pool real), `QuestionPrimitivesTest`, `TranscriptionPipelineE2EIT` | A, I |
| 005 | CA-06 metas do corpus | `UtteranceClassifierTest`: 33/33 perguntas, 0/20 falsos positivos em 53 frases (inclui 5 frases da entrevista real de 2026-11-15) | A |
| 004 | CA-01..CA-06 | `InterviewCopilotServiceTest`, `CopilotAnswerValidatorTest`, `InterviewAnswerCard.test.tsx` | A |
| 004 | CA-07 prompt incremental sem respostas | Prompt alterado + itens `QUESTION` ignorados (`AIInsightExtractorServiceTest` verde) | A |
| 006 | CA-03/CA-04 ponta a ponta | `TranscriptionPipelineE2EIT` (STOMP real via SockJS) | I |
| 008 | CA-01, CA-02, CA-04 eventos de streaming, rejeição e queda | `InterviewCopilotServiceTest`, `TranscriptionPipelineE2EIT` (tópico `/copilot` por STOMP real) | A, I |
| 008 | CA-03 fallback antes do primeiro trecho | `AIOrchestratorStreamingTest` | A |
| 008 | CA-05, CA-06 extração e agrupamento | `StreamingPrimitivesTest` | A |
| 008 | Formato SSE de Grok, Claude e Gemini | `LlmStreamingProvidersTest` (servidor HTTP local reproduzindo os eventos) | A |
| 008 | CA-07..CA-10 UI incremental, modo entrevista, texto provisório | `live.test.tsx`, `copilotStream.test.ts`, `InterviewAnswerCard.test.tsx`, `webSpeechMicSource.test.ts`, `useCaptureSession.test.ts` | A |
| 008 | CA-11 bundle | `npm run check:bundle` | A |
| 008 | CA-12 tempo até o primeiro texto com IA real | Roteiro, seção 6 | **R — pendente** |
| 009 | CA-01..CA-04 opções do seletor, superfície e orientação de "Sem áudio" | `audioSource.test.ts`, `SourceStatusBadge.test.tsx` | A |
| 009 | CA-05 rótulo "Participante remoto" | `SpeakerAttributionServiceTest`, `TranscriptionChunkIT` | A, I |
| 009 | Captura real de YouTube, WhatsApp e tela inteira | Roteiro, seção 7 | **R — pendente** |
| 010 | CA-01..CA-05 captura avulsa | `quickCapture.test.tsx`, `MeetingServiceQuickCaptureTest` | A |
| 011 | CA-01..CA-06 idioma da entrevista | `InterviewLanguageTest`, `GeminiSttLanguageTest`, `MultilingualClassifierTest` (EN 16/16, FR 15/15, 0 falsos positivos), `interviewLanguage.test.ts` | A |
| 011 | Entrevista real em inglês e francês | Roteiro, seção 8 | **R — pendente** |
| Todas | Comportamento com áudio, navegador e provedores reais | Roteiro de validação | **R — pendente** |

## Validação em reunião real
Não executada. Bloqueios atuais:
- Nenhuma chave de IA válida está configurada na organização do usuário. A chave salva antes parecia ser um ID de workspace (`wrkspc_…`).
- Com o STT local desligado por padrão, o áudio da aba precisa de um provedor de STT com chave (Gemini da organização, Deepgram ou OpenAI no servidor).

## Limitações conhecidas
- Corpus de perguntas escrito pelo agente (42 frases): risco de sobreajuste; precisa de frases reais de entrevistas do usuário.
- A heurística anti-invenção (RN-01 da 004) não pega toda paráfrase de experiência inventada.
- Estado de montagem de enunciados, eco e execução serial fica em memória: vale para uma instância do backend; reiniciar perde perguntas em aberto (as já detectadas ficam no banco).
- Latência do copiloto não medida com IA real.
- Streaming (spec 008): o formato dos eventos SSE dos provedores foi reproduzido a partir da documentação, não capturado de uma chamada real. Até o item 6.1 do roteiro passar, existe o risco de o provedor real enviar um formato diferente. Nesse caso a resposta ainda chega inteira no fim (fallback) ou a tentativa falha.
- Riscos de segurança S-01..S-03 (WebSocket sem autenticação, isolamento parcial entre organizações, chaves em texto puro) seguem abertos: spec 007 proposta.
