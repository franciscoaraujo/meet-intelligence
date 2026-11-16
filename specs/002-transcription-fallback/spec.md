# Spec 002 — Fallback de transcrição confiável

Prioridade: **P0** · Status: **Implementada (validada com testes automatizados; validação em reunião real pendente)** · Problemas: P-01, P-06, P-07, P-17 (ver `../000-discovery/diagnostico.md`)

## Contexto e problema
O fluxo ao vivo descarta a maior parte do áudio do entrevistador. `transcribeLiveTabAudioWithOrgAi()` devolve lista vazia quando todos os provedores falham, e `processAudio()` interpreta isso como sucesso. Por isso o `SpeechToTextRouter` (Deepgram → Whisper) nunca é chamado. Os provedores do roteador, por sua vez, devolvem **transcrições inventadas** quando falham ou não têm chave. Na reunião real, 19 de 24 trechos foram perdidos sem tentativa de fallback.

## Objetivo
Toda transcrição deve terminar num de três resultados explícitos: **transcrito**, **sem fala** ou **falha**. Uma falha aciona o próximo provedor configurado, até esgotar a cadeia, e nunca vira texto falso.

## Escopo
- Resultado tipado por provedor e por cadeia (transcrito / sem fala / falha).
- Cadeia de provedores montada a partir da configuração existente: chaves da organização (BYOK) e chaves globais do servidor.
- Timeout e retry limitados por provedor.
- Idempotência por trecho de áudio (`clientSegmentId`).
- Registro do provedor usado e das falhas, sem conteúdo sensível.
- Novo contrato de resposta do `POST /transcripts/chunk`.
- Fim da transcrição simulada fora do modo de desenvolvimento explícito.

## Fora de escopo
- Mudança na captura do frontend (spec 001), exceto o consumo do novo contrato.
- Identificação de falantes (spec 003).
- STT local com Faster-Whisper (evolução P3).
- Streaming de áudio em tempo real (WebSocket de áudio).

## Requisitos funcionais
- **RF-01** Cada provedor de STT devolve `TRANSCRIBED(segmentos)`, `NO_SPEECH` ou `FAILED(motivo)`. Nunca devolve texto que não veio do áudio.
- **RF-02** A cadeia avança para o próximo provedor apenas em `FAILED`. `NO_SPEECH` encerra a cadeia como sucesso sem conteúdo. `TRANSCRIBED` encerra com resultado.
- **RF-03** Ordem da cadeia para áudio ao vivo (origem aba ou microfone), incluindo apenas provedores **disponíveis** (com chave):
  1. Gemini da organização.
  2. gpt-transcribe com a chave OpenAI legada da organização, se existir.
  3. Deepgram do servidor.
  4. gpt-transcribe/Whisper do servidor.
  5. STT local via Python, **somente se** `app.speech.local-google-stt.enabled=true`.
- **RF-04** Ordem para gravação enviada (upload): mantém a preferência atual (Whisper/gpt-transcribe do servidor → Deepgram), aplicando as mesmas regras de resultado.
- **RF-05** Se a cadeia terminar sem `TRANSCRIBED`/`NO_SPEECH`, a resposta é `FAILED`, com lista de provedores tentados e motivo resumido. Nenhum segmento é gravado.
- **RF-06** Trechos menores que o mínimo útil (< 0,3 s de áudio) são `NO_SPEECH` sem chamar provedores.
- **RF-07** Repetir o mesmo `clientSegmentId` na mesma reunião devolve o resultado já gravado, sem nova transcrição nem novo segmento.
- **RF-08** O provedor que produziu cada segmento é gravado em `transcript_segments.source` (ex.: `GEMINI`, `DEEPGRAM`, `OPENAI_TRANSCRIBE`, `LOCAL_GOOGLE_STT`, `WEB_SPEECH`).
- **RF-09** A transcrição simulada só existe com `app.speech.simulation-enabled=true` (padrão `false`) e grava `source=SIMULATION`.

## Requisitos não funcionais
- **RNF-01** Timeout por chamada de provedor: conexão 3 s, leitura 15 s (configuráveis).
- **RNF-02** Retry: no máximo 1 nova tentativa por provedor, apenas para timeout, HTTP 429 e 5xx, com espera de 500 ms. HTTP 4xx (exceto 429) não é repetido.
- **RNF-03** Tempo total da cadeia limitado a 30 s. Ao estourar, a resposta é `FAILED` (`reason=TIMEOUT_BUDGET`).
- **RNF-04** Logs sem texto transcrito nem áudio: só ids, provedor, duração, tamanho, código de erro e latência.
- **RNF-05** Métricas Micrometer: `stt.requests{provider,outcome}`, `stt.latency{provider}` e `stt.chain.exhausted`.

## Regras de negócio
- **RN-01** Lista vazia **não** significa sucesso. Só `NO_SPEECH` declarado por um provedor (resposta válida com texto vazio) ou pela regra RF-06 significa "sem fala".
- **RN-02** Duplicidade de texto em relação ao segmento anterior **da mesma origem** é tratada pela spec 005 (deduplicação), e não como "lista vazia".
- **RN-03** Provedor sem chave não entra na cadeia. Isso não é uma falha.

## Fluxos principais
1. Áudio ao vivo com Gemini da organização funcionando → `TRANSCRIBED` (provedor GEMINI) → segmento gravado → publicado no WebSocket.
2. Gemini falha (HTTP 500) → retry falha → Deepgram transcreve → `TRANSCRIBED` (provedor DEEPGRAM), com a falha do Gemini registrada.
3. Silêncio → Gemini responde vazio → `NO_SPEECH` → nada gravado, sem chamar outros provedores.

## Cenários de erro
| Cenário | Resultado esperado |
|---|---|
| Nenhum provedor configurado | `FAILED` (`reason=NO_PROVIDER_CONFIGURED`), mensagem acionável para a UI |
| Todos falham | `FAILED`, com `attempts=[{provider, reason}]` |
| Timeout de um provedor | Conta como `FAILED` daquele provedor; segue a cadeia |
| Áudio corrompido (não decodificável) | `FAILED` (`reason=INVALID_AUDIO`) sem tentar outros provedores |
| Requisição repetida (mesmo `clientSegmentId`) | Resposta idêntica à primeira, sem efeitos colaterais |
| Reunião inexistente ou de outra organização | HTTP 404 |

## Critérios de aceite
- **CA-01** *Dado* que o Gemini da organização lança erro e o Deepgram do servidor está configurado, *quando* um trecho ao vivo é enviado, *então* o resultado é `TRANSCRIBED` com `provider=DEEPGRAM` e há um log de falha do Gemini sem o texto transcrito.
- **CA-02** *Dado* que todos os provedores falham, *quando* um trecho é enviado, *então* a resposta HTTP é 200 com `status=FAILED`, nenhum segmento é gravado e nenhum texto simulado aparece.
- **CA-03** *Dado* que o primeiro provedor responde sem fala, *quando* o trecho é processado, *então* o resultado é `NO_SPEECH` e nenhum outro provedor é chamado.
- **CA-04** *Dado* que o Deepgram está sem chave e a simulação está desligada, *quando* um upload é processado, *então* o Deepgram não é chamado e não aparece texto de "Carlos Silva".
- **CA-05** *Dado* um provedor que demora mais que o timeout de leitura, *quando* o trecho é processado, *então* a cadeia segue para o próximo em até 15 s + retry.
- **CA-06** *Dado* o mesmo `clientSegmentId` enviado duas vezes, *quando* a segunda chega, *então* a resposta repete a primeira e o banco tem um único segmento.
- **CA-07** *Dado* um HTTP 401 de um provedor, *quando* ocorre, *então* não há retry nesse provedor.

## Segurança e privacidade
- O áudio só é enviado a provedores configurados pela organização ou pelo servidor. O STT local (`recognize_google`, endpoint não oficial) fica desligado por padrão.
- Logs e métricas nunca contêm transcrição, áudio ou chave.
- O acesso ao `/chunk` passa a verificar se a reunião pertence à organização do usuário (corrige S-02 neste endpoint).

## Compatibilidade
- O endpoint `/transcripts/chunk` continua aceitando `{base64Audio, mimeType}`. Os campos novos são opcionais durante a transição.
- A resposta muda de lista para objeto. O frontend é atualizado na mesma entrega (tarefa T-08).
- O fluxo de upload e o `/live-text` continuam funcionando.
- Segmentos antigos não mudam.
