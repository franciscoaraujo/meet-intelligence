# Spec 001 — Captura de áudio (aba do Meet e microfone)

Prioridade: **P0** (processamento do microfone) / **P2** (experiência) · Status: **Implementada (validada com dublês; validação em reunião real pendente)** · Problemas: P-02, P-03, P-04, P-05, P-09

## Contexto e problema
- **Microfone:** é aberto com `getUserMedia`, mas esse áudio não é processado. A voz do candidato vem de uma captura separada da Web Speech API, que também ouve o entrevistador pelo alto-falante e duplica a fala dele.
- **Aba do Meet:** só a trilha de vídeo tem tratamento de encerramento; a trilha de áudio, não.
- **Perda de áudio:** um envio que falha descarta o trecho.
- **Linha do tempo:** cada fonte usa um relógio próprio.

## Objetivo
Capturar aba e microfone como **duas fontes independentes e identificadas**, com estado visível, encerramento detectado, recuperação controlada, sem perda nem duplicidade de trechos e com um relógio único da sessão.

## Escopo
- Módulo de captura reutilizável para as duas fontes: aquisição, VAD (detecção de fala), segmentação e codificação WAV 16 kHz mono.
- Fila de envio com retry idempotente (`clientSegmentId`).
- Estado por fonte exibido na UI.
- Detecção de encerramento e recuperação.
- Relógio da sessão de captura.
- Modo do microfone selecionável: **Pipeline do backend** (padrão proposto) ou **Web Speech** (modo atual, preservado).

## Fora de escopo
- Diarização de vários interlocutores dentro da aba (spec 003, evolução).
- Streaming contínuo por WebSocket de áudio.
- Cancelamento de eco próprio (DSP); será usado só o `echoCancellation` do navegador.

## Requisitos funcionais
- **RF-01** Captura da aba via `getDisplayMedia` com áudio. Se o usuário não marcar "Compartilhar áudio da guia", a UI mostra estado `SEM_AUDIO` com instrução, sem `alert()` bloqueante.
- **RF-02** Captura do microfone via `getUserMedia` com `echoCancellation: true` e `noiseSuppression: true` por padrão, configuráveis.
- **RF-03** No modo **Pipeline do backend**, o áudio do microfone passa pelo mesmo VAD/segmentação da aba e é enviado ao `/transcripts/chunk` com `audioSource=MIC`. No modo **Web Speech**, o comportamento atual é mantido (texto via `/live-text`, `audioSource=MIC`, `source=WEB_SPEECH`).
- **RF-04** Cada fonte expõe um estado: `INATIVA`, `SOLICITANDO_PERMISSAO`, `ATIVA_FALANDO`, `ATIVA_SILENCIO`, `SEM_AUDIO`, `PERMISSAO_NEGADA`, `DISPOSITIVO_INDISPONIVEL`, `INTERROMPIDA`, `RECUPERANDO` ou `ERRO`.
- **RF-05** Encerramento detectado por evento: `ended` de **todas** as trilhas, `mute`/`unmute` e `devicechange`. Ao encerrar, os recursos (`AudioContext`, nós, intervalos) são liberados.
- **RF-06** Recuperação:
  - **Microfone:** até 3 tentativas automáticas de reaquisição (1 s, 3 s, 5 s).
  - **Aba:** como o navegador exige gesto do usuário para compartilhar de novo, a UI mostra o botão "Compartilhar aba novamente", sem reabrir automaticamente.
- **RF-07** Cada trecho recebe `clientSegmentId` (UUID v4), `audioSource`, `captureStartMs` e `captureEndMs` no relógio da sessão (ms desde o início da sessão de captura, via `performance.now()`).
- **RF-08** Envio com fila por fonte: em erro de rede ou HTTP 5xx, há até 3 reenvios com o **mesmo** `clientSegmentId` (backoff 1 s, 2 s, 4 s). A fila tem limite de 20 trechos ou ~3 min de áudio; acima disso, descarta o mais antigo e contabiliza a perda.
- **RF-09** Segmentação: fim do trecho após 1,2 s de silêncio ou 12 s de fala contínua, com 300 ms de pré-roll para não cortar o início.
- **RF-10** Duplicidade:
  - Um trecho nunca é enviado duas vezes com ids diferentes.
  - Com fone de ouvido, as fontes não se misturam.
  - Sem fone, a UI recomenda o uso de fone ao detectar fala simultânea nas duas fontes, e o backend marca eco provável (spec 005).
- **RF-11** Indicadores na UI por fonte: estado, nível (VU), último envio (ok/sem fala/falha/reenviando) e trechos perdidos.

## Requisitos não funcionais
- **RNF-01** Processamento de áudio em `AudioWorklet` (thread de áudio). Atualização do VU no máximo a 10 Hz.
- **RNF-02** Latência entre o fim da fala e o envio ≤ 1,5 s.
- **RNF-03** Nenhum vazamento: após "Parar", nenhuma trilha fica `live` e nenhum `AudioContext` fica aberto.
- **RNF-04** Lógica de captura fora do componente de página (hooks/módulos), coberta por testes unitários.

## Regras de negócio
- **RN-01** A fonte define a origem (`TAB`/`MIC`). Quem falou é decidido pela spec 003, nunca por atividade da outra fonte.
- **RN-02** "Parar gravação" envia os trechos pendentes antes de encerrar, com no máximo 5 s de espera.

## Fluxos principais
1. Iniciar: o usuário clica em "Gravar" → microfone `SOLICITANDO_PERMISSAO` → `ATIVA_SILENCIO`.
2. Aba: o usuário clica em "Capturar aba do Meet" → seleciona a aba com áudio → `ATIVA_*`.
3. O entrevistador fala → trecho TAB enviado → resposta `TRANSCRIBED` → transcrição exibida.
4. O usuário fecha o compartilhamento → aba `INTERROMPIDA` + botão de compartilhar de novo. O microfone continua.

## Cenários de erro
| Cenário | Comportamento |
|---|---|
| Permissão de microfone negada | `PERMISSAO_NEGADA` + instrução para liberar no cadeado da URL; aba continua utilizável |
| Microfone desconectado | `INTERROMPIDA` → `RECUPERANDO` (3 tentativas) → `DISPOSITIVO_INDISPONIVEL` |
| Compartilhamento sem áudio | `SEM_AUDIO` com instrução para marcar "Compartilhar áudio da guia" |
| Backend responde `FAILED` | Trecho marcado como falha na UI; não reenvia (a falha é dos provedores, não do transporte) |
| Rede cai | Reenvio com o mesmo id; ao exceder a fila, contador de perda |
| Navegador sem `AudioWorklet`/`getDisplayMedia` | Mensagem de navegador não suportado para a fonte afetada |

## Critérios de aceite
- **CA-01** *Dado* o modo Pipeline do backend, *quando* o usuário fala no microfone, *então* um `POST /chunk` com `audioSource=MIC` é enviado e o áudio de `getUserMedia` é efetivamente consumido (teste com stream simulado).
- **CA-02** *Dado* a captura da aba ativa, *quando* a trilha de áudio dispara `ended`, *então* o estado vira `INTERROMPIDA`, o `AudioContext` é fechado e os intervalos são limpos.
- **CA-03** *Dado* que o primeiro envio falha com erro de rede, *quando* o reenvio ocorre, *então* usa o mesmo `clientSegmentId` e o trecho aparece uma única vez.
- **CA-04** *Dado* a permissão de microfone negada, *quando* o usuário inicia a gravação, *então* a UI mostra `PERMISSAO_NEGADA` e a captura da aba continua disponível.
- **CA-05** *Dado* o compartilhamento sem áudio, *quando* a seleção termina, *então* o estado é `SEM_AUDIO` e não há `alert()`.
- **CA-06** *Dado* "Parar gravação", *quando* há trechos pendentes, *então* eles são enviados (até 5 s) e depois todas as trilhas ficam `ended`.
- **CA-07** *Dado* o modo Web Speech selecionado, *quando* o usuário fala, *então* o comportamento atual (`/live-text`) é preservado, agora com `audioSource=MIC`.
- **CA-08** *Dado* dois trechos consecutivos, *quando* são enviados, *então* `captureStartMs` é crescente e coerente com a duração do áudio.

## Segurança e privacidade
- O áudio só sai do navegador para o backend da própria aplicação. O modo Web Speech, que envia áudio ao Google pelo navegador, é sinalizado na UI.
- A UI mantém o aviso de consentimento (LGPD) já existente e mostra claramente quando cada fonte está gravando.

## Compatibilidade
- Botões, transcrição manual, upload de arquivo e escolha de falante continuam existindo.
- O modo Web Speech continua disponível.
- O contrato `/chunk` antigo continua aceito pelo backend durante a transição (spec 002).
