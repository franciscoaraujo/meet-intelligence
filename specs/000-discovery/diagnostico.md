# Diagnóstico técnico — Meet Intelligence (Etapa 1 · Discovery)

| Campo | Valor |
|---|---|
| Data | 2026-11-15 |
| Base analisada | Cópia local, branch `feat/knowledge-base` (commit `8a9e07d` + alterações não commitadas: ordenação da transcrição, BYOK por organização, atualização de modelos, Grok). Está à frente do `main` publicado no GitHub. |
| Escopo | Captura de áudio, transcrição, segmentação, IA (insights e copiloto), WebSocket, persistência |
| Alterações de código nesta etapa | Nenhuma |

Evidência de campo usada: reunião real `Teste Meet Intelligence` (2026-11-15), com logs do backend e registros no PostgreSQL.

---

## 1. Arquitetura atual (como está)

```
Navegador (React 19 / LiveMeetingPage.tsx, 1.414 linhas)
 ├─ Aba do Meet: getDisplayMedia → AudioContext 16 kHz → ScriptProcessorNode
 │    → VAD por RMS (> 0,008) → WAV base64 → POST /transcripts/chunk
 ├─ Microfone: getUserMedia (stream aberto e NUNCA consumido)
 │    + Web Speech API (captura própria do navegador) → texto → POST /transcripts/live-text
 ├─ WebSocket STOMP/SockJS: /topic/meetings/{id}/{transcripts|insights|summary}
 └─ Polling a cada 4,5 s: GET insights, summary, transcripts (substitui o estado)

Backend (Spring Boot 3.3 / Java 21)
 TranscriptController
  ├─ /chunk     → TranscriptionService.processAudio(isLiveStream=true)
  │               → transcribeLiveTabAudioWithOrgAi: Gemini (org) → gpt-transcribe (org) → Python recognize_google
  │               → [deveria cair no] SpeechToTextRouter: Deepgram → Whisper
  └─ /live-text → TranscriptionService.processLiveTextSegment (fusão heurística de frases)
        ↓
 TranscriptChunkAggregatorService (buffer em memória, gatilho por palavra-chave)
        ↓ @Async (SimpleAsyncTaskExecutor)
 AIInsightExtractorService ─ PromptManagerService ─ AIOrchestrator (Gemini | Grok | Claude, fallback Gemini)
        ↓
 ai_insights / meeting_tasks / meeting_decisions / meeting_summaries → WebSocket
 EmbeddingIndexingService → pgvector (RAG do chat)
```

Infraestrutura no `docker-compose.yml`: PostgreSQL+pgvector, Redis, RabbitMQ e MinIO. **Redis e RabbitMQ não são usados pelo código**: existe só a entidade `OutboxEvent`, sem publicador nem consumidor.

## 2. Funcionalidades existentes

| # | Funcionalidade | Onde | Estado |
|---|---|---|---|
| F1 | Captura do áudio da aba do Meet com indicador de nível (VU) | `LiveMeetingPage.tsx:322-452` | Funciona, com falhas (P-03, P-04, P-05) |
| F2 | Transcrição da voz do microfone via Web Speech API | `LiveMeetingPage.tsx:489-580` | Funciona só no Chrome/Edge; captura independente (P-02) |
| F3 | Envio manual de fala (campo de texto) e escolha de falante | `LiveMeetingPage.tsx:453-459, 273-276` | Funciona |
| F4 | Transcrição de gravação enviada (upload) | `RecordingService` → `processAudio(isLiveStream=false)` | Funciona; pode gravar transcrição falsa (P-06) |
| F5 | Cadeia de transcrição da aba: Gemini → gpt-transcribe → Google STT via Python | `TranscriptionService.java:110-226` | Fallback quebrado (P-01) |
| F6 | Fusão de fragmentos de fala | `TranscriptionService.java:269-401` | Funde perguntas distintas (P-10) |
| F7 | Agregação em blocos e disparo de IA | `TranscriptChunkAggregatorService.java` | Dispara demais (P-11) |
| F8 | Extração de insights (QUESTION, DECISION, TASK…) e resumo incremental | `AIInsightExtractorService.java` | Duplicidade e vínculo fraco (P-12, P-13) |
| F9 | Copiloto de entrevista (resposta sugerida no card QUESTION) | `PromptManagerService.java`, `GeminiLlmProvider` (simulação) | Responde a menções isoladas (P-14) |
| F10 | Base de Conhecimento (currículo + referências) no prompt | `KnowledgeBaseService.java` | Funciona |
| F11 | Chat RAG com citações | `RagService.java` | Funciona |
| F12 | WebSocket com reconexão automática (5 s) e re-inscrição | `websocket.ts` | Funciona; sem reenvio de eventos perdidos (compensado pelo polling) |
| F13 | BYOK por organização (Gemini, Grok, Claude) com teste de conexão | `OrganizationAiConfigService`, `SettingsPage.tsx` | Corrigido nesta branch (não commitado) |
| F14 | Exportação de ata (PDF/DOCX/TXT) | `ExportController`, `MeetingMinutesService` | Não auditado a fundo (fora do escopo) |

## 3. Problemas, causa raiz, arquivos e impacto

Severidade: **P0** bloqueia o fluxo; **P1** compromete o copiloto; **P2** afeta qualidade/operação; **P3** evolução.

### P-01 · P0 — ✅ resolvido pela spec 002 — Fallback de transcrição nunca é acionado no fluxo ao vivo
- **Sintoma:** 24 trechos de áudio processados e só 5 transcritos na reunião real. Os outros 19 foram descartados sem tentar o Deepgram nem o Whisper.
- **Causa raiz:** `transcribeLiveTabAudioWithOrgAi()` devolve `Collections.emptyList()` em três situações distintas: áudio < 1.500 bytes (linha 112), texto duplicado (linha 209) e **todos os provedores falharam** (linha 225). `processAudio()` testa só `liveSegments != null` (linhas 56-61) e trata a lista vazia como sucesso, então o `SpeechToTextRouter` nunca é chamado com `isLiveStream=true`. Além disso, as falhas internas (Gemini, gpt-transcribe) são engolidas com `log.warn`, sem distinguir "sem fala" de "erro".
- **Arquivos:** `TranscriptionService.java:47-226`, `SpeechToTextRouter.java:21-31`.
- **Impacto:** perda silenciosa da fala do entrevistador, que é justamente a entrada do copiloto.

### P-02 · P0 — ✅ resolvido pela spec 001 — Áudio do microfone capturado não é processado
- **Causa raiz:** `getUserMedia` (linhas 586-594) abre o microfone com `echoCancellation:false`, mas o stream só é guardado em `micStreamRef` e fechado no fim. A transcrição do microfone vem da **Web Speech API**, que faz uma captura própria e independente, sem as configurações pedidas e sem passar pelo backend. São dois fluxos que não se falam.
- **Impacto:**
  1. Depende do Chrome/Edge e do serviço do Google.
  2. Sem controle de eco: a Web Speech também ouve o entrevistador pelo alto-falante e gera **transcrição duplicada** da fala dele, rotulada por heurística (P-08).
  3. O `onend` reinicia o reconhecimento a cada 150 ms indefinidamente, inclusive após erros `network`/`audio-capture` não tratados.
  4. Na reunião real, nenhum segmento do microfone foi gravado.

### P-03 · P1 — ✅ resolvido pela spec 001 — Encerramento e interrupção de fonte mal detectados
- **Causa raiz:** só `getVideoTracks()[0].onended` é tratado (linha 432). O fim da trilha de áudio é detectado apenas pelo polling de 800 ms (linha 379), que marca a fonte como inativa **sem limpar o intervalo nem fechar o `AudioContext`**. Não há eventos para o microfone (`ended`, `devicechange`, `mute`), nem recuperação.
- **Impacto:** o indicador de "ativo" fica incorreto, com vazamento de recursos e sem caminho de recuperação para o usuário.

### P-04 · P1 — ✅ resolvido pela spec 001 — Perda de segmentos de áudio na captura
- **Causa raiz:** se o `POST /chunk` falha, o áudio já saiu do buffer (`pcmChunks = []`) e é descartado (linhas 397-426), sem retry e sem fila. Não existe identificador do trecho para reenvio idempotente.
- **Impacto:** perda de fala durante instabilidade de rede ou do backend.

### P-05 · P2 — ✅ resolvido pela spec 001 — `ScriptProcessorNode` obsoleto na thread principal
- **Causa raiz:** `createScriptProcessor(4096,1,1)` (linha 349) é API descontinuada e roda na thread de UI, que também faz `setTabAudioLevel` a cada ~256 ms e re-renderiza a página de 1.414 linhas.
- **Impacto:** risco de áudio picotado sob carga e de remoção futura pelo navegador.

### P-06 · P0 — ✅ resolvido pela spec 002 — Provedores de STT devolvem transcrição inventada em vez de erro
- **Causa raiz:** `DeepgramSpeechToTextProvider` e `OpenAiWhisperSpeechToTextProvider` chamam `generateMockTranscription()` (falas fixas de "Carlos Silva (Host)" e "Mariana Costa") quando **não há chave e também quando a chamada falha** (Deepgram linhas 49-51, 71-75, 83-85, 133-137; Whisper 51-52, 85-89, 97-99, 155-157).
- **Consequências:** o `catch` do `SpeechToTextRouter` nunca é acionado, porque nada lança exceção, e texto falso é persistido como se fosse da reunião.
- **Dependência crítica:** hoje o P-01 impede que isso aconteça no fluxo ao vivo. **Corrigir o P-01 sem corrigir o P-06 passaria a gravar falas falsas em reuniões reais.**

### P-07 · P1 — ✅ resolvido pela spec 002 — Chamadas HTTP sem timeout e sem política de retry
- **Causa raiz:** todos os clientes usam `new RestTemplate()` sem timeout (`TranscriptionService:45`, provedores de IA e de STT). Não há retry por provedor. O `AIOrchestrator` tem circuit breaker, mas o STT não.
- **Impacto:** uma chamada travada segura a thread HTTP do `/chunk` e a fila do frontend (`isSendingWav`) indefinidamente.

### P-08 · P2 — ✅ resolvido pelas specs 001/003 — Identificação de falante por heurística de atividade
- **Causa raiz:**
  - `resolveActiveSpeaker()` (linha 461) rotula a fala do microfone como "Entrevistador" se a aba teve som nos últimos 2,4 s.
  - O falante padrão é o nome fixo `'Francisco Araújo'` (linha 81).
  - No backend, **toda fala ao vivo é ligada ao primeiro participante** (`existingParticipants.get(0)`, linha 306), inclusive a do entrevistador.
  - O segmento não guarda a origem do áudio (aba/microfone/upload/manual).
  - O campo `source` é fixo em `WEB_SPEECH_LIVE` (linha 317), mesmo para áudio da aba transcrito pelo Google STT; a reunião real confirma.
- **Impacto:** atribuição errada de quem falou, inclusive afirmando identidade sem evidência. Isso alimenta a detecção de perguntas com dados errados.

### P-09 · P2 — ✅ resolvido pelas specs 001/003 — Linha do tempo inconsistente entre fontes
- **Causa raiz:** o microfone usa segundos desde o "gravar" da página (zera ao recarregar). A aba usa `quantidadeDeSegmentos × 5000` (`TranscriptionService:213`). O cliente não envia horário de captura no `/chunk`.
- **Situação:** a ordem da lista já foi corrigida (ordenação por `createdAt`, não commitada), mas os horários `mm:ss` exibidos e a lógica de fusão por `timeGapMs` continuam errados.

### P-10 · P1 — ✅ resolvido pela spec 005 — Fusão de falas une perguntas distintas
- **Causa raiz:** `shouldMergeWithPreviousSegment` funde quando a fala anterior tem ≤ 9 palavras, sem pontuação final, e chegou em < 6 s, comparando relógios inconsistentes (P-09).
- **Evidência:** na reunião real, "explique o conceito de SOLID" e "como você trataria a resiliência…" viraram um único segmento e um único insight.
- **Agravante:** o segmento fundido é reenviado inteiro ao agregador (linha ~295), e o mesmo texto entra de novo no próximo bloco.

### P-11 · P1 — ✅ resolvido pela spec 005 — Gatilho de IA dispara para quase toda fala
- **Causa raiz:** `isImmediateTriggerSegment` dispara para qualquer fala do "entrevistador" ou que contenha `como `, `qual `, `api`, `rest` (casa "restante"), `banco`, `java`, `spring`… Além disso, `MIN_WORDS_PER_CHUNK = 5`. Na prática há uma chamada de LLM por fala.
- **Agravantes:**
  - Os buffers em memória (`ConcurrentHashMap<UUID, StringBuilder>`) têm leitura e escrita não atômicas.
  - O `chunkIndex = count + 1` sofre condição de corrida e não tem restrição de unicidade.
  - `flushRemaining` (botão/fim da gravação) reprocessa os últimos 10 segmentos e gera insights duplicados.
- **Impacto:** custo, ruído no painel e insights repetidos.

### P-12 · P1 — ✅ resolvido pela spec 005 — Deduplicação de insights frágil
- **Causa raiz:** a comparação é só por prefixo de título entre os últimos 4 insights da mesma categoria (`AIInsightExtractorService:148-169`). Tarefas e decisões não são deduplicadas. Insights de blocos processados em paralelo (executor sem limite, sem ordem por reunião) competem entre si.
- **Impacto:** a mesma pergunta aparece várias vezes, e respostas sobrescrevem umas às outras.

### P-13 · P1 — ✅ resolvido pela spec 005 — Insight sem vínculo rastreável com a origem
- **Causa raiz:** `ai_insights` guarda só `start_time_ms`/`end_time_ms` do bloco (com relógio inconsistente), sem `chunk_id`, sem segmentos de origem e sem o texto da pergunta detectada. Se o JSON do LLM falha no parse, o bloco é marcado `processedByAi=true` mesmo assim (linhas 80-83), e o insight é perdido sem rastro.

### P-14 · P1 — ✅ resolvido pela spec 004 — Copiloto responde a menções, não a perguntas
- **Causa raiz:**
  - O prompt base (`PromptManagerService:10-18`) manda gerar um `QUESTION` com resposta completa "sempre que o entrevistador **mencionar** um conceito técnico".
  - Não existe classificação de intenção: pergunta direta, indireta, discussão, comentário ou resposta do candidato.
  - A resposta sai no mesmo JSON da análise incremental (resumo, tarefas, decisões), sem estrutura própria (resposta direta / explicação / exemplo / boas práticas / trade-offs) e sem versão curta para falar.
  - Na simulação (sem chave), `GeminiLlmProvider` (linhas 238-279) detecta pergunta por palavra-chave, usa respostas fixas e **consulta a Wikipédia com a fala da entrevista** (dado enviado a terceiro).
- **Impacto:** respostas genéricas para comentários, e nenhuma diferenciação entre pergunta e resposta do candidato.

### P-15 · P2 — ✅ resolvido pela spec 005 — Concorrência e ordem do processamento assíncrono
- **Causa raiz:** `@Async` roda no `SimpleAsyncTaskExecutor`, com uma thread por tarefa, sem limite e sem fila (confirmado nos logs: `cTaskExecutor-N`). Blocos da mesma reunião são analisados em paralelo e fora de ordem: o resumo incremental (`version`) sofre condição de corrida.

### P-16 · P2 — Observabilidade insuficiente
- **Causa raiz:** logs sem correlação (reunião/segmento/bloco/insight), métricas e status de entrega.
- **Lacunas:**
  - Não há métricas de latência de STT/LLM nem contagem de áudio perdido.
  - Alguns logs registram o **texto transcrito** (`TranscriptionService:214`), um dado pessoal.
  - O Actuator está habilitado, mas sem métricas próprias.

### P-17 · P2 — ✅ resolvido pela spec 002 — STT local via Python sem controle
- **Causa raiz:** `transcribeWavViaLocalGoogleStt` cria um processo `python` por trecho, usando `recognize_google`: endpoint não oficial, sem SLA, com envio do áudio ao Google. A saída padrão é mesclada ao `stderr` (`redirectErrorStream(true)`), então avisos do Python podem virar "transcrição" (só `Traceback` é filtrado). Foi o provedor efetivamente usado na reunião real, com baixa qualidade em termos técnicos.

### P-18 · P2 — ✅ resolvido pela spec 001 — Sincronização redundante na tela
- **Causa raiz:** além do WebSocket, o frontend faz polling a cada 4,5 s e **substitui** insights e transcrições inteiros. Isso compensa eventos perdidos, mas gera tráfego constante e "saltos" visuais. Não existe recuperação incremental após reconexão (ex.: "eventos desde X").

### P-19 · P2 — Ausência de testes nas áreas críticas
- **Backend:**
  - Não há testes do `SpeechToTextRouter`, do fallback, do agregador nem de deduplicação.
  - `TranscriptionServiceTest.shouldProcessAudioAndSaveSegments` **já falha** antes de qualquer mudança.
- **Frontend:** não há executor de testes (nenhum Vitest/Jest no `package.json`).

### Achados transversais de segurança (já relatados na análise inicial; afetam privacidade das entrevistas)

| # | Problema | Onde |
|---|---|---|
| S-01 | WebSocket `/ws/**` sem autenticação, com CORS `*` e credenciais: quem souber o UUID da reunião assina a transcrição | `SecurityConfig`, `WebSocketConfig` |
| S-02 | Acesso a reunião por `findById` sem checar a organização (vazamento entre organizações) | `MeetingService`, `TranscriptionService`, `RagService`, … |
| S-03 | Chaves BYOK gravadas em texto puro | `OrganizationAiConfig` |

Esses itens não estão nas specs 001-006 e devem entrar numa spec própria de segurança (sugestão: `007-security-hardening`), porque o copiloto lida com currículo e conversas de entrevista.

## 4. Dependências entre correções

```
006-T1 (infra de testes)  ──►  todas as demais
002 Fallback STT: P-06 (sem transcrição falsa) ─► P-01 (lista vazia) ─► P-07 (timeout/retry)
002 contrato novo do /chunk (resultado + clientSegmentId + origem + horário)
        ├──► 001 Captura (frontend usa o contrato; fila com retry idempotente)
        └──► 003 Falantes (origem TAB/MIC persistida; fim da heurística)
003 ──► 005 Detecção de perguntas (precisa saber quem falou)
005 ──► 004 Copiloto (responde só a perguntas detectadas; dedup)
P-15 (executor e ordem por reunião) ──► 005/004 (dedup confiável)
```

Regra que não pode ser quebrada: **P-06 antes ou junto com P-01.**

## 5. Riscos de regressão

| Risco | Mitigação |
|---|---|
| Sem transcrição falsa, o modo de desenvolvimento sem chaves deixa de "funcionar" | Simulação só com flag explícita (`app.speech.simulation-enabled`), rotulada na UI |
| Mudar o contrato do `/chunk` quebra o frontend atual | Frontend e backend atualizados na mesma entrega; campos novos opcionais no backend durante a transição |
| Microfone passar a usar STT do backend aumenta custo e latência | Manter a Web Speech como modo alternativo selecionável (não remover a funcionalidade) |
| Menos disparos de IA podem "esconder" uma pergunta real | Critérios de aceite com corpus de perguntas reais (incluindo a reunião de campo) e botão manual "Responder com IA" preservado |
| Nova coluna e índice único em segmentos e insights | Migrations aditivas (colunas opcionais), sem apagar dados existentes |
| Fusão de falas mais conservadora pode quebrar frases longas | Fusão passa a ocorrer só dentro da mesma origem e do mesmo trecho de captura, com testes de regressão das frases da reunião real |
| Remover a consulta à Wikipédia da simulação | Simulação continua respondendo pela Base de Conhecimento e pelas respostas técnicas locais |

## 6. Decisões que dependem de aprovação

1. **Fluxo do microfone:** recomendo usar `getUserMedia → mesmo pipeline do backend` (origem `MIC`) como padrão, mantendo a Web Speech como modo alternativo. Alternativa: manter a Web Speech como padrão e só corrigir o tratamento de eventos.
2. **Transcrição simulada:** desligada por padrão e ligada só por flag de desenvolvimento.
3. **STT local via Python (`recognize_google`):** desligado por padrão (privacidade e qualidade). Faster-Whisper fica como evolução P3, sem substituição obrigatória.
4. **Novas dependências de teste:** Vitest + Testing Library no frontend; Testcontainers (imagem `pgvector/pgvector:pg16`) no backend.
5. **Linha de base:** commitar as alterações pendentes desta branch antes de iniciar a implementação SDD, para que cada spec seja rastreável num diff próprio.
6. **Segurança (S-01..S-03):** criar a spec 007 agora ou depois das specs 001-006.
