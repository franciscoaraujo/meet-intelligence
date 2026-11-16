# Spec 018 — Deepgram como transcrição padrão do produto

Prioridade: **P1** · Status: **Implementada (2026-11-16; testes automatizados, falta a chave real e o CA-07 em entrevista real)** · Origem: decisão do usuário (2026-11-16): "usar o Deepgram para a transcrição e incluir o preço no produto final"

## Problema
O produto vai cobrar um plano com a IA incluída. A transcrição tem de usar **uma chave do servidor**, paga por nós e igual para todos os clientes, e o provedor escolhido é o Deepgram (Nova-3, `pt-BR`).

Hoje isso não acontece, por três motivos:
1. **Ordem da cadeia:** as chaves cadastradas pela organização vêm **antes** das chaves do servidor (spec 002 RF-03, spec 017). Uma organização com chave Gemini ou xAI transcreve com a chave dela, e o Deepgram do servidor nunca é usado.
2. **Custo:** a cobrança precisa saber quanto áudio cada organização transcreveu. A spec 017 só registra o uso do Grok.
3. **Configuração local:** o backend só lê a chave de variáveis de ambiente. Não há um arquivo local, fora do Git, onde colocar a chave sem digitá-la a cada execução.

## Objetivo
Com `DEEPGRAM_API_KEY` configurada no servidor, toda transcrição usa o Deepgram primeiro, e cada minuto transcrito fica registrado por organização. As chaves das organizações viram reserva.

## Requisitos funcionais
- **RF-01 Chaves do servidor primeiro:** nova configuração `app.speech.server-keys-first` (variável `SPEECH_SERVER_KEYS_FIRST`), padrão **`true`**.
  - **Ao vivo, com `true`:**
    1. Deepgram (servidor)
    2. gpt-transcribe (servidor)
    3. Grok (servidor)
    4. Gemini (organização)
    5. gpt-transcribe (organização)
    6. Grok (organização)
    7. STT local (flag)
    8. simulação (flag)
  - **Gravação enviada, com `true`:** Deepgram (servidor) → gpt-transcribe (servidor) → Grok (servidor) → simulação (flag).
  - **Com `false`:** as ordens das specs 002 e 017 ficam exatamente como hoje. É o modo para um plano em que o cliente traz a própria chave.
  - Como sempre, só entram os provedores com chave utilizável.
- **RF-02 Registro de uso de todo STT com preço conhecido:** o registro da spec 017 (RF-07) passa a valer para o Deepgram também:

  | Provedor da cadeia | `provider` | `model_name` | US$ por hora de áudio |
  |---|---|---|---|
  | `DEEPGRAM` | `DEEPGRAM` | `nova-3` | 0,258 (US$ 0,0043/min, pré-gravado, plano pago por uso) |
  | `GROK_STT` | `GROK` | modelo configurado | 0,10 |

  - O cálculo da duração e as regras de quando registrar não mudam: "sem fala" conta; falha não conta.
  - Provedores sem preço na tabela (Gemini, gpt-transcribe, STT local, simulação) continuam sem registro.
- **RF-03 Arquivo local de chaves:** o backend lê, se existir, o arquivo `.env` da pasta de onde é iniciado, no formato `CHAVE=valor`. Para quem roda pelo Maven, é `backend/.env`.
  - O arquivo já está no `.gitignore` e nunca vai para o Git.
  - Variáveis de ambiente continuam valendo e têm prioridade sobre o arquivo.
- **RF-04 Valores de exemplo não contam como chave:** valores começando com `sua-chave` (os do `.env.example`) são tratados como chave ausente, como já acontece com `mock-key`. Assim, quem copiar o exemplo sem preencher não gera chamadas que sempre falham.
- **RF-05 `.env.example`:**
  - o comentário na mesma linha de `DEFAULT_LLM_PROVIDER` vai para uma linha própria, porque no formato do RF-03 ele viraria parte do valor;
  - entra `SPEECH_SERVER_KEYS_FIRST=true`, com uma explicação.
- **RF-06 Log na inicialização:** o backend registra qual provedor de STT ao vivo vem primeiro com as chaves do servidor, sem mostrar o valor da chave. Exemplo: `Transcrição ao vivo: DEEPGRAM primeiro (chave do servidor); chaves da organização como reserva`.

## Regras de negócio
- **RN-01 Chave só no servidor:** a chave do Deepgram nunca vai para o navegador, para log nem para resposta da API.
- **RN-02 Reserva, não substituição:** se o Deepgram falhar (fora do ar, limite de uso), a cadeia continua nos provedores seguintes, como na spec 002. As chaves das organizações servem de reserva.
- **RN-03 Preço numa constante só:** o preço do RF-02 fica em `AiModels`, junto do modelo, para ser atualizado num lugar só quando o Deepgram mudar a tabela.

## Fora do escopo
- Planos, cotas, bloqueio por limite e pagamento (Stripe). Esta spec só registra o uso, que é a base da cobrança.
- Saber se o custo foi pago por nós ou pela chave da organização. `llm_usage` não guarda a origem da chave; com o RF-01 em `true`, o Deepgram registrado é sempre a chave do servidor.
- Modo em tempo real (WebSocket) do Deepgram.
- Custo dos insights da reunião, o maior item medido (cerca de US$ 1,00 por hora). Fica para uma spec própria.

## Critérios de aceite
- **CA-01** Com `server-keys-first=true`, chave Deepgram no servidor e chaves Gemini e xAI na organização: a ordem ao vivo começa por Deepgram e termina pelas chaves da organização (RF-01).
- **CA-02** Com `server-keys-first=false`: as ordens são idênticas às da spec 017 (testes atuais sem mudança).
- **CA-03** Gravação enviada com `true`: o Deepgram vem primeiro.
- **CA-04** Trecho transcrito pelo Deepgram grava uma linha em `llm_usage` com `DEEPGRAM`, `nova-3`, `STT_LIVE` e custo `duração/3600 × 0,258`. Gemini ou gpt-transcribe não gravam nada.
- **CA-05** Valor `sua-chave-deepgram` é tratado como chave ausente.
- **CA-06** Com `backend/.env` contendo `DEEPGRAM_API_KEY`, o backend iniciado pelo Maven usa a chave sem nenhuma variável de ambiente, e o log do RF-06 aparece sem o valor da chave.
- **CA-07** Entrevista real em português com a chave Deepgram do servidor:
  - as perguntas são detectadas e respondidas;
  - os trechos ficam com `source = DEEPGRAM`;
  - o custo por hora registrado em `llm_usage` é anotado aqui.

## Testes
- `TranscriptionChainFactoryTest` (+5):
  - ordem com as chaves do servidor primeiro, ao vivo e em gravação (CA-01, CA-03);
  - o caso real (Deepgram no servidor e só xAI na organização);
  - sem chave no servidor, as da organização continuam funcionando;
  - valores `sua-chave-...` ignorados (CA-05).
  - Os testes anteriores, com `false`, passam sem mudança (CA-02).
- `SttUsageRecorderTest` (+2): Deepgram a US$ 0,258/h (CA-04); provedores sem preço não registram.
- `TranscriptionServiceTest` (+1): trecho do Deepgram registra o uso.
- **CA-06 conferido à mão em 2026-11-16:**
  - com uma chave **falsa** em `backend/.env` e nenhuma variável de ambiente, o log mostrou `Transcrição ao vivo: DEEPGRAM primeiro (chave do servidor); chaves da organização como reserva`, e a chave não apareceu no log;
  - o arquivo foi apagado depois.
- Backend 254, 0 falhas. Invertendo a ordem do servidor, ou tirando o preço do Deepgram, 8 testes falham.

## Riscos
- **Preço do Deepgram:** o valor do RF-02 veio de fontes de terceiros consultadas em 2026-11-16. Confirmar em deepgram.com/pricing antes de fechar o preço do plano.
- **Limite de uso simultâneo:** cerca de 50 requisições pré-gravadas ao mesmo tempo por projeto no plano pago por uso. Basta para o início; acompanhar quando houver muitas entrevistas simultâneas.
