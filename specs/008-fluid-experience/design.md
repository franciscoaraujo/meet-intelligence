# Design 008 — Experiência fluida

## Arquitetura
A arquitetura atual não muda. O caminho continua:

```
spec 005 (DETECTED) → InterviewCopilotService (@Async) → AIOrchestrator → LLMProvider → validador → insight + WebSocket
```

A diferença é que a chamada ao LLM passa a ser em streaming:

```
LLMProvider.stream(...) ──deltas──▶ SpokenAnswerExtractor ──texto──▶ DeltaThrottler ──▶ /topic/meetings/{id}/copilot
        │
        └──texto completo──▶ parse JSON ▶ CopilotAnswerValidator ▶ (como hoje) grava insight, /insights, /questions
                                                     └──▶ COMPLETED ou REJECTED no /copilot
```

## Componentes
| Componente | Camada | Mudança |
|---|---|---|
| `LLMProvider` | domínio | Novo método `default LLMResponse stream(LLMRequest, String apiKey, String model, LlmStreamListener listener)`. A implementação padrão chama `generateCompletion` e emite um único delta (RF-01) |
| `LlmStreamListener` | domínio | Interface nova: `onDelta(String text)`. O fim e o uso de tokens vêm no `LLMResponse` devolvido |
| `SseLineReader` | infraestrutura/ai | Novo. Lê `text/event-stream` de um `HttpClient` do JDK (`BodyHandlers.ofLines()`) e entrega pares `event`/`data`. Respeita o timeout de leitura por linha e o cancelamento |
| `GrokLlmProvider` | infraestrutura/ai | `stream`: Responses API com `"stream": true`; consome `response.output_text.delta` e `response.completed` (uso de tokens) |
| `ClaudeLlmProvider` | infraestrutura/ai | `stream`: Messages API com `"stream": true`; consome `content_block_delta`/`text_delta` e `message_delta` (uso). HTTP direto, como o provedor já faz hoje |
| `GeminiLlmProvider` | infraestrutura/ai | `stream`: `:streamGenerateContent?alt=sse`; cada `data` traz `candidates[0].content.parts[].text` |
| `AIOrchestrator` | aplicação | Novo `executeStreaming(request, meeting, operation, listener)`. Envolve o listener e faz fallback se nenhum delta foi entregue; depois do primeiro delta, propaga o erro (RF-06) |
| `SpokenAnswerExtractor` | aplicação/question | Novo. Máquina de estados sobre o JSON parcial: localiza `"spoken_answer"`, decodifica escapes (incluindo `\uXXXX` e escapes partidos entre pacotes) e libera o texto até a aspa de fechamento. Ignora as cercas de markdown iniciais |
| `DeltaThrottler` | aplicação/question | Novo. Acumula texto e publica no máximo 1 evento a cada 100 ms por pergunta, com descarga final obrigatória (RNF-03) |
| `InterviewCopilotService` | aplicação/question | Usa `executeStreaming`. Publica `STARTED`, `DELTA`, `REJECTED`, `COMPLETED` e `FAILED`. O ciclo de tentativas e o validador continuam iguais |
| `CopilotStreamPublisher` | aplicação/question | Novo. Monta os eventos e chama o `SimpMessagingTemplate` |
| `prompts/interview-answer.v2.md` | recursos | Igual à v1 com `spoken_answer` como primeiro campo e o marcador `INTERVIEW_COPILOT_V2` |
| `AiModels` / `application.yml` | config | `app.ai.{grok,claude,gemini}.copilot-model` |
| `services/websocket.ts` | frontend | Assina `/copilot` |
| `features/copilot/copilotStream.ts` | frontend | Novo. Reducer puro `(estado por questionId, evento) → estado`; ignora `seq` antigo |
| `InterviewAnswerCard.tsx` | frontend | Estados `generating`, `streaming`, `adjusting`, `answered`, `failed`; rascunho com cursor; seções só no `answered` |
| `features/live/mergeById.ts` | frontend | Novo. Mescla listas por id preservando a identidade dos objetos sem mudança e a ordem cronológica |
| `hooks/useStickToBottom.ts` | frontend | Novo. Detecta se o usuário está no fim; expõe a contagem de novas falas |
| `components/live/InterviewModeLayout.tsx` | frontend | Novo. Layout focado (RF-14) |
| `LiveMeetingPage.tsx` | frontend | Usa `mergeById`, `useStickToBottom`, esqueletos e alterna o layout. Partes extraídas para componentes, para a página não crescer além das ~1070 linhas atuais |
| `WebSpeechMicSource` | frontend | Novo callback `onInterim(text)`; o envio ao backend não muda |
| `App.tsx` / `vite.config.ts` | frontend | `React.lazy` + `Suspense` por rota; `manualChunks` para as bibliotecas STOMP/SockJS e de gráficos, se houver |

## Contratos
### Tópico `/topic/meetings/{meetingId}/copilot`
```json
{ "type": "STARTED",   "questionId": "uuid", "attempt": 1, "seq": 0 }
{ "type": "DELTA",     "questionId": "uuid", "attempt": 1, "seq": 3, "text": "Eu usei Kafka para" }
{ "type": "REJECTED",  "questionId": "uuid", "attempt": 1, "seq": 9, "reason": "VALIDATION|STREAM_ABORTED" }
{ "type": "COMPLETED", "questionId": "uuid", "attempt": 2, "seq": 21, "insightId": "uuid" }
{ "type": "FAILED",    "questionId": "uuid", "attempt": 2, "seq": 22, "reason": "LLM_UNAVAILABLE|VALIDATION_FAILED|PARSE_ERROR" }
```
- `DELTA.text` é **incremental**: a UI concatena dentro da mesma `attempt`. Isso economiza banda.
  - Um `STARTED` com `attempt` maior zera o rascunho.
  - `seq` cresce dentro de cada pergunta.
- O motivo de rejeição por validação **não** vai para a UI em detalhe; só a categoria.
- O `COMPLETED` não repete a resposta. A UI usa o insight que já chega por `/insights`, como hoje.
  - Se o `COMPLETED` chegar antes do insight, a UI espera o insight. Se ele não chegar em 2 s, busca por REST.

### Interface de provedor
```java
public interface LlmStreamListener { void onDelta(String text); }

default LLMResponse stream(LLMRequest request, String apiKey, String model, LlmStreamListener listener) {
    LLMResponse full = generateCompletion(request, apiKey, model);
    listener.onDelta(full.getContent());
    return full;
}
```
- O `LLMResponse` devolvido traz o texto completo (para o parse e o validador), o provedor, o modelo e o uso de tokens.

## Eventos e concorrência
- O streaming roda na mesma thread `@Async` do copiloto (pool `taskExecutor` limitado). Não é criada uma thread por delta.
  - O `DeltaThrottler` publica de forma síncrona na própria thread de leitura, porque `convertAndSend` não bloqueia de forma relevante.
- A execução serial por reunião (`MeetingSerialExecutor`) continua só na detecção. Perguntas diferentes podem fazer streaming em paralelo, limitadas pelo pool.
- O timeout por leitura de linha SSE é de 20 s, e o timeout total do copiloto (spec 004) continua valendo. No timeout, a conexão é cancelada (`HttpResponse` + `CompletableFuture.cancel`).

## Tratamento de erros
| Erro | Onde | Resultado |
|---|---|---|
| 4xx/5xx antes do primeiro byte | provedor | Exceção → orquestrador faz fallback |
| Conexão cai após deltas | `SseLineReader` | `StreamAbortedException` → serviço publica `REJECTED/STREAM_ABORTED` e tenta de novo |
| `data` com JSON inválido do provedor | provedor | Linha ignorada + contador `llm.stream.bad_event`; se nada chegar, falha normal |
| JSON final inválido | serviço | Igual a hoje (`PARSE_ERROR`), mais `REJECTED` |

## Observabilidade
- `copilot.ttft` (Timer): do início da execução até o primeiro delta liberado pelo extrator, com tags `provider` e `model`.
- `copilot.latency`: o atual, mais a tag `model`.
- `copilot.stream.fallback`, `copilot.stream.aborted`, `copilot.stream.no_spoken_first` (contadores).
- Log em nível INFO no fim de cada tentativa: `questionId`, tentativa, provedor, modelo, ttft_ms, total_ms e número de deltas. Sem o texto.

## Testes
- **Unitários:**
  - `SpokenAnswerExtractorTest`: partições aleatórias do mesmo JSON, escapes partidos, campo fora de ordem e cercas de markdown.
  - `DeltaThrottlerTest`: com relógio injetável.
  - `SseLineReaderTest`: com um `HttpServer` do JDK local que envia eventos devagar e corta a conexão.
  - Parsers de cada provedor com amostras de eventos SSE gravadas em `src/test/resources/sse/`.
- **Serviço:** `InterviewCopilotServiceTest` com um provedor fake em streaming para os cenários CA-01 a CA-04.
- **Ponta a ponta:** `CopilotStreamingE2EIT`, com STOMP real como na `TranscriptionPipelineE2EIT`: ordem dos eventos e concatenação igual à `spoken_answer` gravada.
- **Frontend:**
  - Reducer `copilotStream` (seq, tentativas).
  - `InterviewAnswerCard` por estado.
  - `mergeById` (identidade preservada).
  - `useStickToBottom`.
  - `InterviewModeLayout` (CA-09).
  - Texto provisório (CA-10).
- **Build:** verificação do tamanho dos chunks em script (`scripts/check-bundle-size.mjs`), rodado após `npm run build`.
- **Real:** item 4.10 do roteiro da spec 006, mais TTFT anotado manualmente e conferido com a métrica `copilot.ttft` do `/actuator/metrics`.

## Decisões
| Decisão | Alternativas | Justificativa |
|---|---|---|
| Fazer streaming só da `spoken_answer`, extraída do JSON | Pedir texto livre e depois o JSON em outra chamada; usar dois formatos na mesma saída (texto + separador + JSON) | Uma chamada só (sem custo nem latência extra), mantém o contrato e o validador da 004, e a `spoken_answer` é o que o candidato lê primeiro |
| Validar no fim e marcar o rascunho como não validado | Validar trechos durante o streaming | O validador precisa do JSON inteiro (`resume_facts_used`); validação parcial daria falso negativo. O rótulo "rascunho" + substituição cumpre o RN-01 |
| `java.net.http.HttpClient` com SSE manual | WebClient/WebFlux; SDKs oficiais (Anthropic Java SDK, etc.) | Sem dependência nova (RNF-04). Os provedores atuais já usam HTTP direto, e trocar só um por SDK quebraria a uniformidade. Pode ser revisto depois |
| Deltas incrementais com `seq` | Mandar o texto acumulado em todo evento | Menos banda; o `seq` resolve reordenação e duplicidade |
| Tópico novo `/copilot` | Reaproveitar `/insights` com um tipo novo | Não quebra clientes atuais nem a lógica de lista de insights |
| Modelo do copiloto por propriedade do servidor | Campo por organização na UI | Evita migration e mudança de tela nesta etapa; o valor pode ir para a organização depois, se a medição mostrar ganho |
| Modo entrevista ligado por padrão para "candidato" | Sempre desligado | É o caso de uso principal do usuário; a escolha manual fica salva |

## Mudanças na implementação (2026-11-15)
| Item | Planejado | Implementado | Motivo |
|---|---|---|---|
| Ordem dos campos (RF-02) | `spoken_answer` como primeiro campo | `is_question_for_candidate` primeiro, `spoken_answer` logo depois (`interview-answer.v2.md`) | O modelo precisa confirmar a pergunta antes de escrever a resposta; o booleano custa poucos tokens |
| Extrator | Libera `spoken_answer` só se vier primeiro | Libera o campo de primeiro nível onde quer que ele apareça; `copilot.stream.no_spoken_first` conta quando nada foi liberado | Mesma segurança (nunca libera JSON cru) com degradação mais suave |
| Claude em streaming | Mesmos parâmetros do modo normal | Sem o fallback do lado da Anthropic (`fallbacks`/`anthropic-beta`) no streaming | Uma reexecução no meio do stream duplicaria texto já mostrado; a falha vai para o `AIOrchestrator` e para as novas tentativas |
| Gemini em streaming | — | Chave no cabeçalho `x-goog-api-key`, não na URL | Não expor a chave em logs de URL |
| Simulação sem chave (RN-03) | Emite em partes | Gemini sem chave emite a simulação em pedaços de 24 caracteres; campos em `LinkedHashMap` com `spoken_answer` no início; o marcador aceita `INTERVIEW_COPILOT_V1` e `V2` | — |
| Motivos de `REJECTED` | `VALIDATION`, `STREAM_ABORTED` | Também `PARSE_ERROR`; `FAILED` também com `NOT_A_QUESTION` | O card precisa limpar o rascunho nesses casos |
| T-13 | `CopilotStreamingE2EIT` separado | Asserções do tópico `/copilot` adicionadas ao `TranscriptionPipelineE2EIT` (copiloto falso em streaming) | Reaproveita o contexto Spring + STOMP real já montado |
| Modo entrevista (RF-14) | Transcrição reduzida a uma faixa; insights acessíveis por aba | A coluna da transcrição continua (mais estreita), com as últimas 3 falas e "Mostrar todas"; insights e chat ficam ocultos e voltam ao sair do modo | Mantém a captura, o campo de texto e os indicadores de fonte sem duplicar a UI |
| Bundle (RF-19) | `React.lazy` + `manualChunks` | `React.lazy` por rota + registro do `@material/web` carregado depois da primeira renderização; sem `manualChunks` | Nenhuma tela usa `<md-*>`; isso tirou ~180 KB do arquivo principal. Resultado: maior chunk 335 KB (antes ~790 KB) |
| Correção encontrada | — | Os cards de pergunta só apareciam se já existisse algum insight | Na primeira pergunta da reunião o rascunho em streaming não apareceria |
| T-23 (medição real) | Executada com chave real | **Não executada.** O teste de fumaça com o Grok real ficou pendente | Sem um token de sessão disponível para chamar a API pela linha de comando; deve ser feito pela interface (roteiro, seção 6) |
| RF-20 (novo) | — | `MicMode = 'OFF'`; `setMicMode` troca o microfone durante a captura (`stopMic`/`startMic`); a sala liga `OFF` ao entrar no modo entrevista e `BACKEND` ao sair | Pedido do usuário depois do primeiro uso real: no modo entrevista a própria voz só atrapalhava (eco e falas duplicadas) |
| RF-20 (revisão) | Efeito na página trocava o `micMode` ao entrar no modo; o seletor permitia religar | `useCaptureSession({ micBlocked })`: `startMic` não liga com o bloqueio, e um efeito desliga/religa só o microfone quando o bloqueio muda durante a captura; o seletor fica desabilitado no modo entrevista | No uso real o microfone voltou a captar no modo entrevista (falas SELF às 22:17-22:18); a regra precisava ser garantida na captura, não por troca de modo |
