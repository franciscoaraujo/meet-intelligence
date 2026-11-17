# Spec 020 — Início e fim da captura num lugar só, e arquivar em vez de excluir

Prioridade: **P1** · Status: **Implementada (2026-11-16; testes automatizados; falta o CA-09 em entrevista real)** · Origem: relatos do usuário (2026-11-16): "para que serve o botão de iniciar a captura se parece que está funcionando sem ele" e "quero persistir as transcrições para criar um histórico"

## Problema
**1. Dois botões para começar, e o que se chama "Iniciar" não liga a escuta.**
- "Áudio do computador" (`capture.startTab`) abre o compartilhamento e já transcreve. É dele que vêm as perguntas e as respostas do copiloto.
- "Iniciar captura" (`capture.startRecording`) marca a reunião como `IN_PROGRESS`, liga o cronômetro e liga **o microfone**. No modo entrevista o microfone fica bloqueado (spec 008, RF-20), então o botão só liga o cronômetro.

**2. O fechamento depende do botão que parece inútil.**
- "Encerrar" só aparece depois de "Iniciar captura".
- É ele que envia os trechos pendentes, marca `COMPLETED` e dispara a análise final (`/insights/trigger`: resumo e ata).
- Quem só compartilha o áudio termina com a reunião eternamente `SCHEDULED` e sem análise final, o que dificulta encontrá-la no histórico.

**3. Excluir apaga o histórico para sempre.**
- As transcrições são gravadas (`transcript_segments`), mas o botão de excluir da página Reuniões apaga a reunião e, em cascata, transcrições, perguntas, respostas, ata e gravações.
- Em 2026-11-16, o banco tinha 382 registros de uso de IA sem reunião associada: entrevistas inteiras perdidas por exclusão.

## Objetivo
Um único ponto de início e de fim, que funcione de qualquer jeito que a pessoa comece. O histórico só sai da lista por **arquivamento**, que pode ser desfeito. A exclusão definitiva continua existindo para o direito ao esquecimento (LGPD), separada e com confirmação forte.

## Requisitos funcionais

### Ciclo da captura
- **RF-01 Um botão "Iniciar":** o botão principal do cabeçalho passa a se chamar **"Iniciar"** e, num único clique:
  1. abre o compartilhamento de áudio (`startTab`);
  2. fora do modo entrevista, liga também o microfone no modo escolhido;
  3. marca a reunião como `IN_PROGRESS` e liga o cronômetro.
  - Se a pessoa cancelar o compartilhamento, nada é iniciado: o status e o cronômetro não mudam, e aparece a mensagem atual de compartilhamento cancelado.
- **RF-02 Qualquer fonte inicia a sessão:** ligar o áudio do computador pelo seu botão próprio (ou o microfone, fora do modo entrevista) com a sessão parada também inicia a sessão: `IN_PROGRESS`, cronômetro e "Encerrar" visível. Não importa por onde a pessoa comece.
- **RF-03 "Encerrar" sempre disponível:** enquanto houver sessão iniciada, aparece "Encerrar", que faz o fechamento completo de hoje:
  - para todas as fontes, enviando os trechos pendentes;
  - marca `COMPLETED`;
  - dispara a análise final.
- **RF-04 Fonte que cai não encerra a sessão:** se o compartilhamento da aba for interrompido (estado `INTERROMPIDA`, já existente), a sessão continua com o aviso e o botão "Compartilhar de novo". Só "Encerrar" fecha a sessão.
- **RF-05 Sair da página com a sessão aberta:** a página pede confirmação ao fechar ou recarregar com a sessão iniciada (`beforeunload`). Se a pessoa sair mesmo assim, a reunião fica `IN_PROGRESS` e pode ser encerrada depois pela própria sala (RF-06).
- **RF-06 Reabrir uma reunião em andamento:** ao abrir uma reunião `IN_PROGRESS` sem captura ativa neste navegador, a sala mostra **"Encerrar sessão"** (marca `COMPLETED` e dispara a análise final, sem fonte para parar) e **"Retomar"** (RF-01).
- **RF-07 Status também do lado do servidor:**
  - o primeiro trecho transcrito de uma reunião `SCHEDULED` muda o status para `IN_PROGRESS`;
  - isso cobre o caso em que o navegador não conseguiu marcar o status (falha de rede).

### Arquivar em vez de excluir
- **RF-08 Arquivar:**
  - nova coluna `meetings.archived_at` (migration);
  - `POST /api/v1/meetings/{id}/archive` e `POST /api/v1/meetings/{id}/unarchive`;
  - arquivar só esconde: nenhum dado é apagado.
- **RF-09 Lista de reuniões:**
  - `GET /meetings` traz só as não arquivadas, como padrão;
  - `GET /meetings?archived=true` traz as arquivadas;
  - a página Reuniões ganha a aba **"Arquivadas"**, com o botão "Restaurar".
- **RF-10 Ação principal:** o botão do card na página Reuniões passa a ser **"Arquivar"**, com uma confirmação simples ("A reunião sai da lista e pode ser restaurada em Arquivadas").
- **RF-11 Exclusão definitiva separada:**
  - "Excluir definitivamente" fica só nas reuniões **arquivadas**;
  - a confirmação exige digitar o título da reunião ou a palavra `EXCLUIR`;
  - o texto deixa claro que transcrição, perguntas, respostas, ata e gravações serão apagadas e não podem ser recuperadas;
  - o endpoint `DELETE /meetings/{id}` continua existindo, com a guarda da spec 007.
- **RF-12 Painel e métricas:** reuniões arquivadas continuam contando nas métricas de uso (`llm_usage`), porque o custo aconteceu, mas não aparecem nas listas de "recentes" do painel.

## Regras de negócio
- **RN-01** Nenhum caminho comum da interface apaga dados de entrevista; só a exclusão definitiva do RF-11.
- **RN-02** O status da reunião nunca volta de `COMPLETED` para `SCHEDULED`. "Retomar" uma reunião concluída a leva para `IN_PROGRESS` de novo, e a análise final roda outra vez ao encerrar.
- **RN-03** O modo entrevista continua sem microfone (spec 008, RF-20); RF-01 e RF-02 respeitam isso.

## Fora do escopo
- Busca no histórico e prazo de guarda (spec 021).
- Gravação do áudio bruto da entrevista.
- Encerramento automático por tempo de silêncio.

## Critérios de aceite
- **CA-01** No modo entrevista, "Iniciar" abre o compartilhamento, e com ele aceito a reunião fica `IN_PROGRESS`, o cronômetro corre e "Encerrar" aparece. Cancelando o compartilhamento, nada disso acontece.
- **CA-02** Ligar só o "Áudio do computador" com a sessão parada também leva a `IN_PROGRESS`, cronômetro e "Encerrar".
- **CA-03** "Encerrar" para as fontes, envia os pendentes, marca `COMPLETED` e chama a análise final uma vez.
- **CA-04** Compartilhamento interrompido pelo Chrome: a sessão continua, com o aviso e "Compartilhar de novo".
- **CA-05** Reunião `IN_PROGRESS` aberta sem captura ativa mostra "Encerrar sessão" e "Retomar"; "Encerrar sessão" marca `COMPLETED`.
- **CA-06** O primeiro trecho transcrito de uma reunião `SCHEDULED` a leva para `IN_PROGRESS` no servidor.
- **CA-07**
  - Arquivar esconde a reunião da lista e a mostra em "Arquivadas", com transcrição, perguntas e respostas intactas no banco; "Restaurar" a devolve.
  - Arquivar ou restaurar reunião de outra organização → 404 (spec 007).
- **CA-08** "Excluir definitivamente" só aparece em arquivadas e só confirma com o título ou `EXCLUIR` digitado.
- **CA-09** Uma entrevista real feita só com "Iniciar" e "Encerrar", no modo entrevista, termina `COMPLETED`, com a análise final e visível no histórico.

## Implementação
- **Ciclo da sessão:** `frontend/src/features/live/captureSession.ts`, separado da página para ser testado sem áudio real.
  - `startTab` agora devolve se o compartilhamento foi aceito;
  - `startRecording({ withMic })` inicia a sessão com ou sem microfone.
- **Sala ao vivo:**
  - "Iniciar" no lugar de "Iniciar captura";
  - "Retomar" e "Encerrar sessão" (RF-06);
  - confirmação ao sair com a sessão aberta (RF-05).
- **Backend:**
  - migration `V14__meeting_archive.sql` (`meetings.archived_at` e índice);
  - `POST /meetings/{id}/archive` e `/unarchive`; `GET /meetings?archived=`;
  - recusa de voltar para `SCHEDULED` (RN-02); "Retomar" uma concluída limpa `actual_end`;
  - primeira fala transcrita leva a `IN_PROGRESS` (RF-07).
- **Página Reuniões:**
  - abas "Reuniões" e "Arquivadas";
  - "Arquivar" com confirmação simples;
  - "Restaurar" e "Excluir definitivamente" só nas arquivadas;
  - confirmação digitada (`ConfirmDialog.requireTyping`).
- **Painel:** a lista de reuniões recentes usa `GET /meetings`, que agora traz só as ativas (RF-12). As métricas de uso não mudam.
- **Não implementado no RF-02:** começar a sessão **só pelo microfone**, fora do modo entrevista. Trocar o modo do microfone com a sessão parada continua só guardando a escolha; quem quer só o microfone usa "Iniciar", que também liga o microfone. O caso que motivou a spec, começar pelo "Áudio do computador", está coberto.

## Testes
- Frontend:
  - `captureSession.test.ts` (9): CA-01 a CA-05, RF-01, RF-02, RF-04;
  - `useCaptureSession.test.ts` (+3): compartilhamento aceito ou cancelado, sessão sem microfone;
  - `LiveMeetingHeader.test.tsx` (+3): "Iniciar", "Retomar" e "Encerrar sessão";
  - `archive.test.tsx` (6): lista ativa, arquivar, aba Arquivadas, restaurar, exclusão com confirmação digitada (CA-07, CA-08).
- Backend:
  - `MeetingLifecycleIT` (3, servidor real e PostgreSQL real): arquivar, restaurar e dados intactos; outra organização → 404; regras de status (CA-07, RN-02);
  - `TranscriptionServiceTest` (+2): primeira fala leva a `IN_PROGRESS` (CA-06); concluída não muda;
  - `MigrationsIT` (+1): coluna `archived_at`.
- Backend 296, frontend 148, 0 falhas; build e bundle ok.
- **Verificação de mutação:** com a lista ignorando o arquivamento, sem o status automático na primeira fala, iniciando mesmo com o compartilhamento cancelado ou sem iniciar a sessão pelo "Áudio do computador", 4 testes falham.
- **V14** aplicada no banco local (o backend recarregou sozinho).

