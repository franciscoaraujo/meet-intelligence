# Spec 014 — Estilo das respostas do copiloto

Prioridade: **P2** · Status: **Implementada (2026-11-16; testes automatizados)** · Origem: pedido do usuário ("configurar as respostas: informal, formal e técnica")

## Objetivo
Escolher o tom das respostas do copiloto, mantendo a fidelidade ao currículo e o tamanho da resposta falada.

## Requisitos funcionais
- **RF-01** Estilos: `NEUTRAL` (padrão: tom profissional, como hoje), `INFORMAL`, `FORMAL` e `TECHNICAL`.
- **RF-02** O estilo é gravado na reunião (coluna `answer_style`, migration V10, padrão `NEUTRAL`). Assim o copiloto o usa também nas respostas automáticas.
- **RF-03** `PATCH /api/v1/meetings/{id}/answer-style?style=…` grava o estilo (só na própria organização); valor inválido → 400.
- **RF-04** Seletor "Estilo" no cabeçalho da sala, ao lado do idioma. A escolha vale para as próximas respostas ("Gerar de novo" aplica num card existente) e fica lembrada no navegador para a próxima "Nova captura".
- **RF-05** Fora do `NEUTRAL`, o prompt do copiloto recebe `<answer_style>` com a instrução do estilo:
  - **INFORMAL:** tom de conversa, frases curtas, menos jargão, sem gíria pesada.
  - **FORMAL:** vocabulário cuidado, sem gírias, estrutura contexto → solução → resultado.
  - **TECHNICAL:** termos precisos, nomes de padrões, trade-offs e números do currículo quando houver, sem inflar perguntas simples.

## Regras de negócio
- **RN-01** O estilo muda o tom, nunca os fatos: a fidelidade ao currículo e o validador continuam iguais.
- **RN-02** A resposta falada continua entre 60 e 140 palavras; a profundidade automática (BASIC/INTERMEDIATE/ADVANCED) continua valendo.
- **RN-03** O estilo combina com o idioma (spec 011).

## Critérios de aceite
- **CA-01** `style=FORMAL` grava; `style=XYZ` → 400 sem gravar.
- **CA-02** Reunião `TECHNICAL` → o prompt do copiloto contém a instrução técnica; `NEUTRAL` → sem `<answer_style>`.
- **CA-03** Reuniões existentes ficam `NEUTRAL` após a migration.
- **CA-04** O seletor chama o endpoint, e "Nova captura" usa o último estilo escolhido.

## Implementação
- Backend: `AnswerStyle` (enum com a instrução de cada tom), migration `V10__meeting_answer_style.sql`, `PATCH /meetings/{id}/answer-style`, bloco `<answer_style>` no `CopilotPromptBuilder`.
- Frontend: seletor "Estilo" no cabeçalho (`LiveMeetingHeader`), preferência no navegador (`answerStyle.ts`), "Nova captura" com o último estilo.
- Testes: `AnswerStyleTest` (2), `MigrationsIT` (+1, PostgreSQL real), `answerStyle.test.ts` (2), `LiveMeetingHeader.test.tsx` (+1). Backend 170, frontend 108.
