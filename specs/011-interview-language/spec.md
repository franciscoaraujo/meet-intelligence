# Spec 011 — Idioma da entrevista (português, inglês, francês)

Prioridade: **P2** · Status: **Implementada (2026-11-15; testes automatizados, uso real pendente)** · Origem: pedido do usuário ("selecionar o idioma… toda a captura e respostas serão no idioma selecionado"). Substitui a ideia de um botão de tradução.

## Contexto e problema
A reunião já tem um campo `language` (padrão `pt-BR`), e os provedores de transcrição recebem esse valor. Mas várias peças estão fixas em português:
- O prompt de transcrição do Gemini manda transcrever "em português do Brasil".
- O microfone do navegador usa `recognition.lang = 'pt-BR'`.
- O copiloto escreve em português.
- O detector de perguntas só tem regras em português ("como", "qual", "explique…"). Numa entrevista em inglês ou francês, nenhuma pergunta seria detectada sozinha.
- O validador anti-invenção só procura frases de experiência em português ("trabalhei com").
- Não há como trocar o idioma na interface.

## Objetivo
Escolher o idioma da sessão (português, inglês ou francês) e fazer a captura, a detecção de perguntas e as respostas do copiloto funcionarem nesse idioma.

## Escopo
1. Seletor de idioma na sala ao vivo; o valor é gravado na reunião e lembrado no navegador para as próximas "Nova captura".
2. Transcrição no idioma (Gemini, STT local, Deepgram, OpenAI, microfone do navegador).
3. Detecção de perguntas com regras em inglês e francês.
4. Copiloto respondendo no idioma (resposta falada, seções, pergunta interpretada, observações).
5. Validador anti-invenção com frases de experiência em inglês e francês.

## Fora de escopo
- Interface do app, insights, resumo, ata e chat RAG: continuam em português.
- Outros idiomas.
- Detecção automática do idioma.

## Requisitos funcionais
- **RF-01** `PATCH /api/v1/meetings/{id}/language?language=pt-BR|en-US|fr-FR` grava o idioma da reunião (só da própria organização). Qualquer outro valor → 400.
- **RF-02** Seletor "Idioma" no cabeçalho da sala (Português, English, Français), utilizável também durante a captura. A troca vale para os próximos trechos.
- **RF-03** A última escolha fica no navegador. "Nova captura" (spec 010) cria a sessão com ela.
- **RF-04** O prompt de transcrição do Gemini usa o idioma da reunião. Os demais provedores já recebem o código BCP-47.
- **RF-05** O microfone em modo navegador (Web Speech) usa o idioma da reunião.
- **RF-06** O classificador de perguntas (spec 005) usa as regras do idioma da reunião: começo interrogativo, pedidos ("explain", "tell me", "expliquez", "parlez-moi"), pergunta no meio da fala ("how would you…", "comment feriez-vous…") e falsas perguntas ("as I said", "comme je disais").
- **RF-07** O copiloto recebe `<answer_language>` e escreve `spoken_answer`, `sections`, `question` e `assumptions` no idioma. Em português, o prompt não muda.
- **RF-08** O validador reconhece experiência em primeira pessoa em inglês ("I worked with", "I used", "in my experience") e francês ("j'ai travaillé", "j'ai utilisé", "dans mon expérience"), com a mesma regra: só fatos do currículo.

## Critérios de aceite
- **CA-01** Dado `language=en-US`, então a reunião é gravada com `en-US`; dado `language=es-ES`, então 400.
- **CA-02** Dada uma reunião em `fr-FR`, então o prompt de transcrição do Gemini pede francês.
- **CA-03** Corpus em inglês e francês: ≥ 90 % das perguntas detectadas e ≤ 10 % de falsos positivos; o corpus em português continua com as metas.
- **CA-04** Dada uma reunião em `en-US`, então o prompt do copiloto contém a instrução de responder em inglês; em `pt-BR`, não contém.
- **CA-05** "I worked with Kafka at Acme" sem Kafka no currículo → rejeitada; o mesmo em francês.
- **CA-06** Trocar o idioma na sala chama o endpoint e atualiza o seletor; a escolha vira o idioma da próxima "Nova captura".

## Compatibilidade
Reuniões existentes continuam em `pt-BR`. O comportamento em português não muda.
