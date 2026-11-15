# Spec 005 — Detecção de perguntas e deduplicação

Prioridade: **P1** · Status: **Aprovada (2026-11-15), não iniciada** · Problemas: P-10, P-11, P-12, P-13, P-15

## Contexto e problema
- **Disparo excessivo:** o agregador chama a IA para quase toda fala (palavras como "como ", "api", "rest", "java"; mínimo de 5 palavras).
- **Fusão errada:** a fusão de falas une perguntas distintas.
- **Duplicidade:** a deduplicação compara só prefixos de título, e o mesmo texto fundido é reenviado ao agregador.
- **Concorrência:** blocos da mesma reunião são analisados em paralelo e fora de ordem.
- **Rastreabilidade:** os insights não têm vínculo com o trecho de origem.

Na reunião real, duas perguntas viraram um único insight.

## Objetivo
Detectar **perguntas reais** (diretas, indiretas e técnicas sem "?"), considerando quem falou e o contexto. Cada pergunta deve gerar **um único** registro, rastreável até os segmentos de origem, entregue ao copiloto (spec 004) uma única vez.

## Escopo
- Classificação de cada fala finalizada (enunciado).
- Junção de continuação entre segmentos da **mesma origem**.
- Janela de espera para a pergunta terminar.
- Deduplicação por conteúdo, contexto e janela temporal.
- Rastreabilidade: `question_id`, segmentos de origem e timestamp.
- Processamento ordenado por reunião.
- Gatilho manual "Responder com IA" preservado.

## Fora de escopo
- Geração da resposta (spec 004).
- Análise de resumo, tarefas e decisões: continua no fluxo de blocos, com gatilho próprio, menos frequente.

## Requisitos funcionais
- **RF-01** Cada segmento `TRANSCRIBED` alimenta o detector com origem, papel (spec 003), texto e tempo.
- **RF-02** Classificação do enunciado em: `DIRECT_QUESTION`, `INDIRECT_QUESTION` (ex.: "me fale sobre…", "gostaria de entender como você…"), `TECHNICAL_DISCUSSION`, `CONTEXT_COMMENT`, `CANDIDATE_ANSWER` ou `OTHER`, com pontuação (0-1).
- **RF-03** Sinais considerados:
  - Pontuação e estrutura: "?", interrogativos no início, inversões.
  - Verbos de pedido ou imperativo: explique, descreva, conte, fale, compare, como você faria…
  - Papel do falante: `REMOTE` com usuário `Candidato` aumenta o peso; `SELF` com usuário `Candidato` vira `CANDIDATE_ANSWER`.
  - Presença de conteúdo técnico, como **reforço**, nunca como gatilho isolado.
  - Contexto: pergunta em aberto anterior, ainda sem resposta.
- **RF-04** Continuidade: fragmentos consecutivos da **mesma origem** com intervalo ≤ 2,5 s formam um único enunciado, até a pergunta "fechar". A pergunta fecha com 1,5 s sem nova fala da mesma origem, com o início de fala da outra origem (resposta começou) ou ao atingir 40 palavras.
- **RF-05** Uma pergunta só segue para o copiloto se `tipo ∈ {DIRECT_QUESTION, INDIRECT_QUESTION}` e `pontuação ≥ 0,6`. `TECHNICAL_DISCUSSION` com pontuação ≥ 0,8 gera **sugestão de ponto de contexto**, sem resposta completa.
- **RF-06** Deduplicação: uma pergunta nova é **a mesma** de uma existente na reunião quando a similaridade de conteúdo (Jaccard sobre radicais normalizados, reaproveitando `TextRelevance`) é ≥ 0,6 e o intervalo entre elas é ≤ 120 s. Nesse caso, atualiza a pergunta existente (texto mais completo e segmentos acrescentados), sem criar nova nem chamar o copiloto de novo, a menos que o texto tenha crescido ≥ 30 %.
- **RF-07** Eco provável: um segmento `MIC` com similaridade ≥ 0,8 a um segmento `TAB` dos últimos 5 s é marcado como `echo_suspected` e **não** gera pergunta nem resposta.
- **RF-08** Cada pergunta detectada tem `question_id`, `meeting_id`, texto, tipo, pontuação, ids dos segmentos de origem, `capture_start_ms`/`capture_end_ms` e status (`DETECTED`, `ANSWERING`, `ANSWERED`, `FAILED`, `DISMISSED`).
- **RF-09** O gatilho manual ("Responder com IA") cria a pergunta a partir dos últimos segmentos `REMOTE` em aberto, ignorando o limiar da RF-05 e respeitando a deduplicação.
- **RF-10** Processamento por reunião em ordem de chegada, com no máximo uma análise de pergunta por vez por reunião.
- **RF-11** A fusão de segmentos (`shouldMergeWithPreviousSegment`) só ocorre dentro da mesma origem e com relógio de captura confiável. Ela não reenvia ao agregador o texto já processado: envia só o delta.

## Requisitos não funcionais
- **RNF-01** Detecção local (sem LLM), com latência < 20 ms por segmento.
- **RNF-02** Precisão em corpus de teste (≥ 40 frases em pt-BR, incluindo as da reunião real): ≥ 90 % de perguntas reais detectadas e ≤ 10 % de falsos positivos em comentários e menções técnicas.
- **RNF-03** No máximo **1** chamada de copiloto por pergunta (salvo crescimento ≥ 30 %, RF-06).
- **RNF-04** Executor de tarefas assíncronas com limite (threads e fila), no lugar do `SimpleAsyncTaskExecutor`.

## Regras de negócio
- **RN-01** A menção isolada de um termo técnico ("usamos Kafka aqui") **não** gera resposta completa.
- **RN-02** A fala do próprio candidato nunca é respondida pelo copiloto.
- **RN-03** Na dúvida (pontuação entre 0,4 e 0,6), a pergunta fica `DETECTED` sem resposta automática, e a UI oferece "Responder".

## Fluxos principais
1. Entrevistador (TAB): "Explique o conceito de SOLID" → `DIRECT_QUESTION` 0,9 → aguarda 1,5 s → fecha → copiloto.
2. Entrevistador: "Como você trataria a resiliência…" logo depois → **nova** pergunta (similaridade baixa com a anterior), e não fusão.
3. Entrevistador repete "Pode explicar SOLID de novo?" após 30 s → mesma pergunta (dedup) → sem nova chamada.
4. Candidato (MIC) responde → `CANDIDATE_ANSWER` → fecha a pergunta em aberto.

## Cenários de erro
| Cenário | Comportamento |
|---|---|
| Transcrição truncada ("explique o conceito de") | Aguarda a continuidade (RF-04); fecha por tempo |
| Pergunta sem "?" vinda do STT | Detectada pelo verbo de pedido ou pela estrutura |
| Falha do copiloto | Status `FAILED`, com "Tentar novamente" na UI |
| Reinício do servidor com pergunta em aberto | Perguntas `DETECTED` persistidas; o estado em memória da janela é perdido e documentado como limitação |

## Critérios de aceite
- **CA-01** *Dado* "explique o conceito de SOLID" seguido em 3 s de "como você trataria a resiliência de microsserviços", *quando* processados, *então* existem **duas** perguntas distintas.
- **CA-02** *Dado* "aqui a gente usa Kafka e Spring", dito pelo entrevistador, *quando* processado, *então* não há resposta completa do copiloto.
- **CA-03** *Dado* "me fale um pouco sobre sua experiência com mensageria" (sem "?"), *quando* processado, *então* é `INDIRECT_QUESTION` com pontuação ≥ 0,6.
- **CA-04** *Dado* a mesma pergunta repetida em 30 s, *quando* processada, *então* há um único registro, e o copiloto é chamado uma única vez.
- **CA-05** *Dado* fala `MIC` idêntica a uma fala `TAB` de 2 s antes, *quando* processada, *então* é marcada `echo_suspected` e não gera pergunta.
- **CA-06** *Dado* o corpus de teste, *quando* o detector roda, *então* atinge as metas da RNF-02.
- **CA-07** *Dado* uma pergunta respondida, *quando* consultada, *então* lista os ids dos segmentos de origem e o timestamp de captura.
- **CA-08** *Dado* 3 segmentos da mesma reunião chegando simultaneamente, *quando* processados, *então* as análises ocorrem em ordem, sem duplicidade.

## Segurança e privacidade
- A detecção é local, sem envio a terceiros.
- Logs registram tipo, pontuação e ids, sem o texto.

## Compatibilidade
- Os insights existentes continuam exibidos.
- A análise de resumo, tarefas e decisões continua, agora com gatilho por volume (≥ 120 palavras ou 60 s) e no fim da reunião.
- O botão "Responder com IA" continua existindo.
