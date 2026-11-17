# Spec 017 — Transcrição com o Grok (xAI)

Prioridade: **P1** · Status: **Implementada (2026-11-16; testes automatizados, falta o CA-09 em entrevista real)** · Origem: pedido do usuário (2026-11-16), avaliação de produto (desktop ou SaaS)

## Problema
Hoje a transcrição ao vivo de quem usa só a chave do Grok cai no **STT local** (`LOCAL_GOOGLE_STT_ENABLED`). Ele é um script Python que chama um endpoint **não oficial** do Google:
- não tem contrato nem SLA e pode ser bloqueado a qualquer momento;
- não é autorizado para uso comercial;
- o áudio vai para o Google sem acordo nenhum;
- exige Python na máquina.

Ele serve para desenvolvimento, mas não para um produto vendido.

A cadeia da spec 002 já tem provedores oficiais (Gemini, gpt-transcribe, Deepgram), mas cada um exige outra chave. A organização que só cadastrou a chave do Grok, usada nas respostas, fica sem nenhum provedor oficial.

## Objetivo
Com **uma única chave** da xAI, a organização tem transcrição e respostas do copiloto. O Grok passa a ser um provedor oficial na cadeia de transcrição, e o STT local deixa de ser necessário quando essa chave existe.

## API usada
Referência: [xAI — Speech to Text](https://docs.x.ai/developers/rest-api-reference/inference/speech-to-text), consultada em 2026-11-16.

- **Endpoint:** `POST https://api.x.ai/v1/stt` (multipart), `Authorization: Bearer <chave xAI>`.
- **Campos usados:**
  - `model=grok-voice-transcribe-2.0`;
  - `language` (código ISO do idioma da entrevista, spec 011: `pt`, `en` ou `fr`);
  - `file` (**obrigatoriamente o último campo** do formulário).
- **Formatos:** o contêiner é detectado sozinho. Cobre WAV, que é o que a captura envia hoje (`segmentUploader.ts`), além de webm, ogg, mp3 e m4a das gravações.
- **Resposta:** `text`, `language`, `duration` (segundos) e `words[]` (`text`, `start`, `end`, `confidence`).
- **Preço publicado:** US$ 0,10/h (REST) e US$ 0,20/h (streaming).
- **Fora desta spec:** o modo WebSocket em tempo real (`wss://api.x.ai/v1/stt`) e a separação de falantes (`diarize`).

## Requisitos funcionais
- **RF-01 Provedor:** `GrokSttProvider` implementa `AudioTranscriber`, com `providerName() = "GROK_STT"`, e segue as regras de resultado da spec 002 (RF-01):
  - `text` vazio ou só espaços → `NO_SPEECH`;
  - texto presente → `TRANSCRIBED` com um segmento:
    - o texto inteiro;
    - `startTimeMs`/`endTimeMs` do primeiro e do último item de `words`, ou 0 quando `words` não vier;
    - confiança média de `words`, ou 0,95 quando ela não vier;
    - o idioma pedido (spec 011);
  - erro → `FAILED` pela classificação existente (`SttFailures`):
    - 401/403: chave inválida, não repete;
    - 429/5xx/timeout: repete conforme a spec 002;
    - 413 (áudio grande demais): `INVALID_AUDIO`, encerra a cadeia, porque os outros provedores também o recusariam;
    - 400: `HTTP_4XX`, passa ao próximo provedor. Um 400 pode ser problema da requisição e não do áudio, e encerrar a cadeia deixaria o trecho sem transcrição.
- **RF-02 Modelo configurável:** `app.ai.grok.stt-model` (variável `GROK_STT_MODEL`), padrão `grok-voice-transcribe-2.0`.
- **RF-03 Ordem da cadeia ao vivo** (atualiza o RF-03 da spec 002). Entram só os provedores com chave utilizável (`isUsableKey`):
  1. Gemini (organização)
  2. gpt-transcribe (chave OpenAI da organização)
  3. **Grok (chave xAI da organização)** ← novo
  4. Deepgram (servidor)
  5. gpt-transcribe (servidor)
  6. **Grok (chave xAI do servidor, `XAI_API_KEY`)** ← novo
  7. STT local (flag)
  8. simulação (flag)
- **RF-04 Ordem para gravação enviada** (atualiza o RF-04 da spec 002):
  1. gpt-transcribe (servidor)
  2. Deepgram (servidor)
  3. **Grok (servidor)** ← novo
  4. simulação (flag)
- **RF-05 Chave da organização:** usa a mesma `grok_api_key` que já existe em `organization_ai_configs` para as respostas, respeitando `use_organization_keys`. Não há campo novo nem migration.
- **RF-06 Origem do trecho:** os trechos transcritos pelo Grok são gravados com `source = "GROK_STT"`. A métrica de tentativas da cadeia (spec 002) passa a incluir esse provedor.
- **RF-07 Registro de uso:** cada áudio processado pelo Grok grava uma linha em `llm_usage`, inclusive quando o resultado é "sem fala", porque a xAI cobra pelo áudio enviado. É a base para cotas e cobrança de um SaaS:
  - `provider = GROK`, `model_name` = modelo de STT, `operation_type = STT_LIVE` ou `STT_BATCH`;
  - tokens 0;
  - `estimated_cost_usd = duração / 3600 × 0,10`. A duração vem do cabeçalho WAV no áudio ao vivo e, nas gravações, do fim da última palavra. A resposta da xAI traz `duration`, mas o contrato dos provedores (`TranscriptionOutcome`) não carrega esse valor, e mudá-lo afetaria todos os provedores;
  - `latency_ms` da chamada.
- **RF-08 Tela de configuração de IA:** o texto do campo da chave do Grok passa a dizer que ela serve para respostas **e transcrição**. Não há campo novo.

## Regras de negócio
- **RN-01 Sem mudança para quem já tem outras chaves:** o Grok entra **depois** dos provedores atuais do mesmo nível (organização ou servidor). Organizações que já transcrevem com Gemini, OpenAI ou Deepgram continuam iguais. O Grok só vira o primeiro quando é a única chave, o caso que motivou a spec.
- **RN-02 STT local continua só para desenvolvimento:** ele não sai do código, mas deixa de ser necessário quando há chave xAI. Com a flag ligada e um provedor oficial disponível, ele só roda se todos os oficiais falharem, como já acontece hoje.
- **RN-03 Sem texto inventado:** como em toda a spec 002, nunca se grava texto que não veio do provedor.
- **RN-04 Chave nunca exposta:** a chave não aparece em log, em mensagem de erro nem em resposta da API, e a chamada sai só do backend. O motivo da falha guarda o status HTTP, não o corpo com a chave.
- **RN-05 Palavras de hesitação:** a xAI remove "é…", "hum" e similares por padrão (`filler_words=false`). Mantemos o padrão, porque deixa a pergunta mais limpa para o classificador (spec 005).

## Fora do escopo
- Streaming por WebSocket, com texto parcial e detecção de fim de fala (`speech_final`). Pode reduzir a latência e fica para uma spec futura, se a medição pedir.
- Separação de falantes (`diarize`), útil para vários entrevistadores na aba (evolução P3).
- `keyterm` com termos do currículo e da vaga para acertar jargão técnico. Candidato a uma spec própria.
- Cotas, planos e cobrança. Esta spec só grava o uso (RF-07).
- Remover o STT local do código.

## Critérios de aceite
- **CA-01** Organização com só a chave xAI, sem chave de STT no servidor e com o STT local desligado: um trecho de fala ao vivo é transcrito pelo Grok e gravado com `source = GROK_STT`.
- **CA-02** A requisição enviada tem `model`, `language` (ISO do idioma da entrevista) e `file` como **último** campo, com `Authorization: Bearer`.
- **CA-03** Resposta com `text` vazio → `NO_SPEECH`, e a cadeia termina sem chamar o próximo provedor.
- **CA-04** 401 do Grok → `FAILED` sem nova tentativa, e a cadeia passa ao próximo provedor. 429/503 → nova tentativa conforme a spec 002. 413 → a cadeia termina como áudio inválido. 400 → passa ao próximo provedor.
- **CA-05** Ordem: com chaves Gemini e xAI na organização, o Gemini vem antes do Grok. Com só a xAI na organização e Deepgram no servidor, o Grok vem antes do Deepgram.
- **CA-06** Gravação enviada com só `XAI_API_KEY` no servidor: o Grok transcreve.
- **CA-07** Cada áudio processado pelo Grok (com ou sem fala) grava uma linha em `llm_usage` com `STT_LIVE` ou `STT_BATCH` e custo `duração/3600 × 0,10`. Falha, outro provedor ou erro ao gravar o uso não registram nada e não derrubam a transcrição.
- **CA-08** Nenhum log ou resposta de erro contém a chave.
- **CA-09** Entrevista real em português com só a chave xAI e `LOCAL_GOOGLE_STT_ENABLED` desligado:
  - as perguntas são detectadas e respondidas normalmente;
  - a latência do trecho (fim da fala → texto) é medida e anotada aqui.

## Testes
- `GrokSttProviderTest` (10), com servidor HTTP simulado:
  - campos e ordem do multipart (CA-02);
  - modelo configurável (RF-02);
  - `TRANSCRIBED` com tempos e confiança vindos de `words`, e o padrão quando `words` não vem (RF-01);
  - `NO_SPEECH` (CA-03);
  - classificação de 401/429/503/413/400 (CA-04);
  - a chave fora do motivo da falha, mesmo quando o corpo do erro a repete (CA-08);
  - na cadeia: o Grok transcreve (CA-01), 401 passa ao próximo, 503 repete, 413 encerra (CA-04).
- `TranscriptionChainFactoryTest` (+5): ordens do RF-03 e do RF-04, `use_organization_keys` (CA-05, CA-06).
- `SttUsageRecorderTest` (5): custo ao vivo e de gravação, "sem fala", casos sem registro, erro ao gravar (CA-07).
- `TranscriptionServiceTest` (+2): trecho gravado como `GROK_STT` com uso registrado; outro provedor não registra.
- Backend 246, frontend 120. Desligando a chave xAI da organização na cadeia, ou o tratamento do 413, 5 testes falham.
- **CA-09** só pode ser feito com a chave real numa entrevista real. Testes com servidor simulado não comprovam a qualidade nem a latência.

## Riscos
- **Qualidade em português no áudio da aba** (compressão do Meet, ruído): ainda não medida. Se o resultado for pior que o atual, a organização pode cadastrar outra chave, e a ordem do RN-01 a respeita.
- **Latência:** o REST recebe o trecho inteiro depois da pausa, como os provedores atuais. Se ficar lenta, a saída é o modo WebSocket (fora do escopo).
- **API recente:** campos e preço podem mudar. O modelo é configurável (RF-02), e o preço do RF-07 fica numa constante única.
