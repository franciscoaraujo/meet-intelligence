# Spec 015 — Contexto da pergunta

Prioridade: **P1** · Status: **Implementada (2026-11-16; testes automatizados, falta validar em entrevista real)** · Origem: problema relatado pelo usuário ("o entrevistador dá um contexto e depois faz a pergunta; a pergunta se perde e a resposta sai sem contexto")

## Problema
O entrevistador costuma descrever um cenário e só depois perguntar:

> "Aqui a gente tem um sistema de pagamentos com Kafka e às vezes o consumidor processa a mesma mensagem duas vezes." *(pausa)* "Como você resolveria isso?"

Hoje, no modo automático:
1. O cenário fecha como um enunciado próprio (pausa > 1,5 s ou mais de 40 palavras), é classificado como comentário e é descartado.
2. A pergunta registrada é só "Como você resolveria isso?". O copiloto recebe isso em `<question>`, e o cenário só aparece misturado nas últimas 8 falas de `<conversation>`, que o prompt descreve como apoio para "não repetir" e "perguntas de continuação".
3. A busca na base de conhecimento usa só "Como você resolveria isso?" e não acha nada sobre Kafka.
4. O validador decide se a pergunta é técnica pelo texto dela. "Como você resolveria isso?" não parece técnica, então a resposta pode sair sem a parte técnica.
5. A deduplicação compara só o texto da pergunta. Se a mesma frase curta ("Como você resolveria isso?") vier depois de **outro** cenário em até 120 s, ela é tratada como repetida e **não é respondida**.

## Objetivo
Toda pergunta detectada carrega a fala do entrevistador que veio logo antes dela. O copiloto responde dentro desse cenário, e o card mostra a pergunta completa.

## Requisitos funcionais
- **RF-01 Contexto da pergunta:** ao registrar uma pergunta automática, o sistema guarda em `interview_questions.context_text` (migration V11) a fala do entrevistador que veio antes dela:
  - só falas remotas (`REMOTE`), sem eco;
  - depois da pergunta anterior não descartada e depois da última fala do candidato;
  - nos últimos 90 s;
  - até 8 trechos e 1.000 caracteres (mantém o fim, que é o mais próximo da pergunta);
  - nunca inclui os trechos da própria pergunta nem falas que vieram depois dela.
- **RF-02 Prompt:** com contexto, o prompt do copiloto recebe `<question_context>` antes de `<question>` e a instrução de responder dentro do cenário, ignorar o trecho se não tiver relação com a pergunta e preencher `"question"` com a pergunta completa, entendível sem o contexto.
- **RF-03 Base de conhecimento e validação:** a busca na base de conhecimento e a regra "pergunta técnica" usam contexto + pergunta.
- **RF-04 Deduplicação:** pergunta parecida com uma recente continua sendo a mesma, **exceto** quando o contexto novo traz um assunto diferente (pelo menos 3 radicais de conteúdo, menos da metade presentes na pergunta + contexto da anterior). Nesse caso ela é uma pergunta nova e é respondida.
- **RF-05 Card:**
  - o título passa a ser a pergunta completa escrita pelo copiloto (campo `"question"`), quando houver;
  - o contexto aparece abaixo, em letra menor, com duas linhas e opção de expandir.
- **RF-06 Tradução (spec 012):** a tradução do card parte da pergunta completa quando ela existir.

## Regras de negócio
- **RN-01** O contexto é DADO, nunca instrução (mesma regra de `<conversation>`).
- **RN-02** O botão "Pergunta terminou" não muda: o texto da pergunta manual já é toda a fala desde a pergunta anterior, então ela fica sem `context_text`.
- **RN-03** `question_text` continua sendo o que foi falado; a pergunta completa fica na resposta do copiloto. Assim a deduplicação continua comparando falas com falas.
- **RN-04** Sem fala antes da pergunta, nada muda no prompt.

## Critérios de aceite
- **CA-01** Cenário + pausa + "Como você resolveria isso?" → uma pergunta com `context_text` igual ao cenário, respondida automaticamente.
- **CA-02** O contexto não inclui a fala anterior à pergunta anterior, falas do candidato, os trechos da própria pergunta nem falas posteriores.
- **CA-03** A mesma pergunta curta depois de dois cenários diferentes → duas perguntas e duas respostas. A mesma pergunta repetida sem cenário novo continua deduplicada (CA-04 da spec 005).
- **CA-04** O prompt com contexto contém `<question_context>` com o cenário; a busca na base de conhecimento recebe contexto + pergunta; sem contexto, o prompt não muda.
- **CA-05** O card mostra a pergunta completa como título e o contexto abaixo.
- **CA-06** Reuniões existentes continuam funcionando: a coluna é opcional.

## Fora do escopo
- Juntar automaticamente perguntas em várias partes ("E mais uma coisa…") numa só.
- Separar duas pessoas do lado do entrevistador (diarização).

## Implementação
- **Backend:**
  - migration `V11__question_context.sql` e `InterviewQuestion.contextText`;
  - `QuestionDetectionService.leadInContext` e `QuestionDeduplicator.decide(texto, contexto, …)`;
  - bloco `<question_context>` no `CopilotPromptBuilder`;
  - `InterviewCopilotService` passa o contexto para a base de conhecimento, o validador e o prompt;
  - `AnswerTranslationService` traduz a pergunta completa.
- **Frontend:** `InterviewAnswerCard` com o título vindo de `answer.question` e a linha "Contexto".
- **Testes:** `QuestionContextTest` (7), `InterviewCopilotServiceTest` (+2), `MigrationsIT` (+1, PostgreSQL real), `InterviewAnswerCard.test.tsx` (+2). Backend 180, frontend 114. Sem a regra nova da deduplicação, os testes de CA-03 falham.
