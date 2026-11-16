# Spec 016 — Pergunta em várias partes

Prioridade: **P1** · Status: **Planejada** · Origem: pedido do usuário (2026-11-16), continuação da spec 015 ("juntar uma pergunta que vem em várias partes")

## Problema
O entrevistador costuma completar a pergunta depois de uma pausa:

> "Como você faria o cache desse serviço?" *(pausa)* "E mais uma coisa, como você invalidaria esse cache?"

Hoje isso vira **dois cards**. O primeiro responde só à primeira parte. O segundo responde ao complemento sem a primeira parte, porque a spec 015 só pega como contexto a fala que veio **depois** da pergunta anterior. O candidato precisa juntar as duas respostas de cabeça enquanto fala.

A deduplicação (spec 005) não resolve. Ela só junta textos parecidos, e o complemento costuma falar de outra coisa ("invalidar" e "fazer o cache" têm pouco em comum).

## Objetivo
Um complemento dito logo depois de uma pergunta entra no **mesmo card**. A resposta é refeita cobrindo todas as partes, na ordem em que foram ditas.

## Definições
- **Pergunta principal:** a pergunta mais recente que não foi descartada nem juntada a outra.
- **Complemento:** pergunta nova, detectada automaticamente, que cumpre o RF-01.

## Requisitos funcionais
- **RF-01 Detecção do complemento:** uma pergunta nova é complemento da principal quando cumpre **todas** as condições abaixo:
  1. a deduplicação (spec 005) a classificou como `NEW`;
  2. o fim da fala dela está a até **30 s** do fim da fala da pergunta principal (`capture_end_ms`; sem esse dado, vale `created_at`);
  3. começa com um marcador de continuação do idioma da entrevista (spec 011), depois de normalizar o texto como o classificador faz:
     - **PT:** "e mais uma coisa", "mais uma coisa", "outra coisa", "além disso", "e também", "e ainda", "complementando", "só complementando", "aproveitando", "e se", "e como", "e qual", "e quais", "e por que", "e quando", "e onde", "e o que";
     - **EN:** "and also", "also", "one more thing", "another thing", "on top of that", "following up", "and how", "and what", "and why", "and when", "and which", "what if", "and if";
     - **FR:** "et aussi", "encore une chose", "autre chose", "de plus", "en plus", "et comment", "et pourquoi", "et quand", "et quel", "et quelle", "et si";
  4. não há fala do candidato (`SELF`, sem eco) entre as duas perguntas;
  5. o contexto antes dela (spec 015) não traz assunto novo, pela mesma regra da deduplicação (RF-04 da spec 015);
  6. a principal tem menos de 3 partes.
- **RF-02 Registro:** o complemento é gravado como pergunta própria, com status `MERGED` e `merged_into_id` apontando para a principal (migration V12).
  - O `question_text` da principal **não muda**.
  - Os trechos (`source_segment_ids`) e o fim da captura (`capture_end_ms`) do complemento são somados à principal.
- **RF-03 Nova resposta:** a principal é respondida de novo **no mesmo card** (`answer_version` + 1, como no "Gerar de novo"):
  - **principal `ANSWERED` ou `FAILED`:** gera a nova resposta na hora;
  - **principal `ANSWERING`:** a geração em curso é **substituída**:
    - ela para de publicar rascunhos e o resultado dela não é gravado;
    - o tópico `/copilot` recebe `REJECTED` com motivo `SUPERSEDED`;
    - a nova geração começa com todas as partes;
  - **principal `DETECTED` sem resposta automática (pontuação intermediária):** a principal continua sem resposta automática. O complemento passa a fazer parte dela quando o usuário pedir a resposta.
- **RF-04 Prompt:** com mais de uma parte, `<question>` traz as partes numeradas, na ordem em que foram ditas, junto com a instrução `<multi_part>`:
  - responder a todas as partes, na ordem, numa única `spoken_answer`, com uma transição natural entre elas;
  - escrever em `"question"` a pergunta completa, juntando as partes.
- **RF-05 Tamanho:** com mais de uma parte, a `spoken_answer` pode ter até **180 palavras** (o limite continua 60–140 com uma parte). O validador e o texto do prompt usam o mesmo limite.
- **RF-06 Card:**
  - selo "2 partes" ou "3 partes";
  - título com a pergunta completa escrita pelo copiloto (como na spec 015);
  - ação **"Separar"**, que desfaz a junção:
    - o complemento volta a ser uma pergunta própria (`DETECTED`, sem `merged_into_id`) e é respondido em card próprio;
    - a principal é respondida de novo só com as partes que sobraram.
- **RF-07 API:** `POST /api/v1/meetings/{meetingId}/questions/{questionId}/split`:
  - recebe o id do complemento e só vale na própria organização;
  - id que não é de um complemento → 409;
  - as perguntas alteradas são publicadas em `/topic/meetings/{id}/questions`.
- **RF-08 Lista de perguntas:** complementos (`MERGED`) não aparecem como item próprio na lista do modo entrevista; aparecem dentro do card da principal.

## Regras de negócio
- **RN-01** Na dúvida, não junta: sem marcador de continuação, a pergunta é nova. Juntar errado custa mais que dois cards, porque a resposta passa a falar de duas coisas.
- **RN-02** O botão "Pergunta terminou" (RF-09 da spec 005) não junta nada. Ele já usa toda a fala desde a última pergunta, e um complemento conta como "última pergunta" para esse limite.
- **RN-03** A deduplicação (spec 005) compara as perguntas novas com a principal **e** com os complementos dela. Um complemento repetido não gera nova resposta.
- **RN-04** Uma geração substituída nunca grava resposta nem atualiza o card, mesmo se terminar depois da nova.
- **RN-05** Logs e métricas sem o texto falado:
  - `questions.merged`: complementos juntados;
  - `questions.split`: junções desfeitas pelo usuário;
  - `copilot.superseded`: gerações substituídas.

  A proporção `split / merged` mede a precisão da regra.

## Critérios de aceite
- **CA-01** "Como você faria o cache desse serviço?" + 5 s + "E mais uma coisa, como você invalidaria esse cache?" → um card com 2 partes e uma resposta que cobre as duas. O complemento fica `MERGED` e aponta para a principal.
- **CA-02** O complemento chega enquanto a principal está `ANSWERING` → a geração antiga emite `REJECTED`/`SUPERSEDED` e não grava nada. A nova resposta gravada é a de 2 partes, com `answer_version` 1 se a antiga não chegou a gravar.
- **CA-03** Não junta:
  - "E como foi no seu último emprego?" 45 s depois;
  - a mesma frase sem marcador ("Como foi no seu último emprego?");
  - um complemento depois de uma fala do candidato;
  - um complemento depois de um cenário novo (spec 015);
  - uma quarta parte.
- **CA-04** "Separar" → dois cards: o complemento é respondido sozinho e a principal é refeita só com a parte dela. Id que não é complemento → 409.
- **CA-05** Com 2 partes, uma `spoken_answer` de 170 palavras é aceita; com 1 parte, é rejeitada (limite de 140).
- **CA-06** Marcadores em inglês e francês juntam com a reunião no idioma correspondente. Em português, "and also" não junta.
- **CA-07** Reuniões existentes continuam funcionando: `merged_into_id` é opcional e nenhuma pergunta antiga muda de status.

## Fora do escopo
- Decidir a junção com um modelo de IA, sem marcador de continuação. Fica como evolução se a métrica `split / merged` mostrar que os marcadores perdem muitos casos.
- Juntar complementos ao card de uma pergunta feita pelo botão "Pergunta terminou".
- Separar duas pessoas do lado do entrevistador (diarização), registrado em [ideias-futuras](../ideias-futuras.md).

## Implementação prevista
- **Backend:**
  - **Banco:** migration `V12__question_parts.sql`, com `interview_questions.merged_into_id UUID NULL REFERENCES interview_questions(id)` e um índice. O status `MERGED` entra em `InterviewQuestion.Status`.
  - **Domínio:** `domain/question/FollowUpDetector`, com os marcadores por idioma e a regra do RF-01. Função pura, testada com um corpus por idioma, como `question-corpus-*.csv`.
  - **Detecção:** `QuestionDetectionService.process`, depois da deduplicação: se for complemento, grava como `MERGED`, atualiza a principal e pede a nova resposta.
  - **Copiloto:**
    - `InterviewCopilotService` passa a ter um contador de geração por pergunta, em memória. A tentativa em curso confere esse contador antes de publicar cada delta e antes de gravar; se mudou, encerra com `SUPERSEDED`.
    - O prompt é montado com as partes (principal + complementos ordenados por `capture_start_ms`).
  - **Prompt e validação:** `CopilotPromptBuilder` com o bloco `<multi_part>`; `CopilotAnswerValidator.validate` recebe o número de partes (limite 180).
  - **API:** `QuestionController` com `POST …/split`.
- **Frontend:**
  - `InterviewAnswerCard`: selo de partes e ação "Separar";
  - `InterviewModeLayout`: esconde os complementos da lista;
  - `copilotStream.ts`: trata `SUPERSEDED` como `REJECTED`, descartando o rascunho.
- **Testes previstos:**
  - `FollowUpDetectorTest` (corpus PT/EN/FR, positivos e negativos);
  - `MultiPartQuestionTest`: CA-01 a CA-04 na detecção e no copiloto, incluindo a substituição em curso;
  - validador (CA-05) e `MigrationsIT` (CA-07);
  - card: selo, "Separar" e o rascunho descartado em `SUPERSEDED`.
