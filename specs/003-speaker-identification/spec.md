# Spec 003 — Identificação de falantes

Prioridade: **P2** (fundação necessária para a 005) · Status: **Rascunho, aguardando aprovação** · Problemas: P-08, P-09

## Contexto e problema
Quem falou é decidido hoje por heurística:
- A fala do microfone vira "Entrevistador" se a aba teve som nos últimos 2,4 s.
- O nome padrão é fixo (`'Francisco Araújo'`).
- No backend, toda fala ao vivo é ligada ao primeiro participante da reunião.
- O segmento não guarda a origem do áudio.
- O campo `source` mente para o áudio da aba (`WEB_SPEECH_LIVE`).

Isso afirma identidades sem evidência e contamina a detecção de perguntas.

## Objetivo
Cada segmento registra **de onde veio** (origem), **quem provavelmente falou** (papel/falante), **com que confiança** e **quando**. Na falta de evidência, usa "desconhecido".

## Escopo
- Atributos de origem e de falante no segmento.
- Regras determinísticas de atribuição por origem.
- Uso da diarização do provedor quando ele a fornecer (Deepgram).
- Configuração do papel do usuário na reunião ("sou o candidato").
- Exibição na UI.

## Fora de escopo
- Diarização própria por IA, reconhecimento de voz por biometria e identificação nominal de múltiplos entrevistadores. Evolução P3, a ser avaliada com dados.

## Requisitos funcionais
- **RF-01** Todo segmento novo tem `audio_source` ∈ {`TAB`, `MIC`, `UPLOAD`, `MANUAL`}.
- **RF-02** Todo segmento novo tem `speaker_role` ∈ {`SELF`, `REMOTE`, `UNKNOWN`} e `speaker_confidence` (0-1).
- **RF-03** Regras de atribuição:

  | Origem | Papel | Rótulo exibido | Confiança | Participante vinculado |
  |---|---|---|---|---|
  | `MIC` | `SELF` | Nome do usuário logado (ou "Você") | 0,9 | Participante do usuário logado |
  | `TAB` sem diarização | `REMOTE` | "Participante remoto (Meet)" | 0,8 | Nenhum |
  | `TAB` com diarização do provedor | `REMOTE` | "Participante remoto N" | Valor do provedor | Participante "remoto N" criado por reunião |
  | `UPLOAD` com diarização | `UNKNOWN` | "Falante N" | Valor do provedor | Participante "Falante N" (comportamento atual) |
  | `UPLOAD` sem diarização / `MANUAL` | Escolhido pelo usuário ou `UNKNOWN` | Rótulo escolhido ou "Desconhecido" | 1,0 se escolhido, senão 0 | Conforme a escolha |
- **RF-04** Na reunião do tipo entrevista, o usuário define seu papel (`Candidato` ou `Entrevistador`). Isso mapeia `SELF`/`REMOTE` para papéis de entrevista usados pela spec 005. Padrão: `Candidato`.
- **RF-05** O frontend **não** deduz o falante pela atividade de outra fonte. `resolveActiveSpeaker()` é removido.
- **RF-06** Falas simultâneas nas duas fontes viram dois segmentos com intervalos de tempo sobrepostos. Nenhum é descartado (a eco provável é tratada pela spec 005).
- **RF-07** Os segmentos guardam `capture_start_ms`/`capture_end_ms` no relógio da sessão (spec 001). A UI mostra esse horário.
- **RF-08** `source` registra o provedor real de transcrição (spec 002, RF-08).
- **RF-09** O usuário pode corrigir o rótulo de um segmento (como já corrige o texto). A correção vira confiança 1,0.

## Requisitos não funcionais
- **RNF-01** Atribuição em O(1) por segmento, sem chamadas externas adicionais.
- **RNF-02** Modelo de dados preparado para diarização futura: `speaker_tag` (já existe) + `speaker_confidence`.

## Regras de negócio
- **RN-01** Nunca exibir um nome de pessoa para `REMOTE` sem evidência (correção manual ou fonte confiável).
- **RN-02** A fala `SELF` em entrevista com papel `Candidato` é tratada como resposta do candidato pela 005.

## Fluxos principais
1. Trecho `MIC` transcrito → segmento `SELF`, rótulo com o nome do usuário, confiança 0,9.
2. Trecho `TAB` transcrito pelo Gemini (sem diarização) → `REMOTE`, "Participante remoto (Meet)".
3. Upload com o Deepgram (diarização) → "Falante 1/2…", com participantes criados por tag.

## Cenários de erro
| Cenário | Comportamento |
|---|---|
| Requisição sem `audioSource` (cliente antigo) | `audio_source=TAB` no `/chunk`, `MANUAL` no `/live-text`; papel `UNKNOWN` |
| Usuário logado sem participante na reunião | Cria o participante do usuário (papel HOST) na primeira fala `MIC` |
| Diarização com confiança < 0,5 | Rótulo "Participante remoto" (sem número) e `UNKNOWN` |

## Critérios de aceite
- **CA-01** *Dado* um trecho `TAB`, *quando* é gravado, *então* tem `speaker_role=REMOTE`, nenhum participante vinculado e o rótulo não contém o nome do usuário.
- **CA-02** *Dado* um trecho `MIC`, *quando* é gravado, *então* tem `speaker_role=SELF` e fica vinculado ao participante do usuário logado.
- **CA-03** *Dado* fala no microfone enquanto a aba tem som, *quando* ambos são transcritos, *então* existem dois segmentos com origens diferentes, e o do microfone **não** é rotulado como entrevistador.
- **CA-04** *Dado* um upload transcrito com diarização de 2 falantes, *quando* é gravado, *então* surgem "Falante 1" e "Falante 2", com a confiança do provedor.
- **CA-05** *Dado* uma requisição sem `audioSource`, *quando* chega, *então* é aceita com papel `UNKNOWN` (compatibilidade).
- **CA-06** *Dado* a correção manual do rótulo de um segmento, *quando* é salva, *então* a confiança vira 1,0 e a mudança aparece no WebSocket.

## Segurança e privacidade
- Não há biometria de voz.
- Rótulos com nomes só vêm do usuário logado ou de correção manual.

## Compatibilidade
- Os campos novos são opcionais. Segmentos antigos ficam com `audio_source`/`speaker_role` nulos e a UI os exibe como hoje.
- O seletor manual de falante continua existindo para o envio manual de texto.
