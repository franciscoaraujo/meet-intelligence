# Spec 010 — Captura avulsa (sem Google Meet)

Prioridade: **P2** · Status: **Implementada (2026-11-15; testes automatizados)** · Origem: pedido do usuário. A sala ao vivo só abre a partir de uma reunião, e a criação exige link do Meet.

## Contexto e problema
- O modal "Nova reunião" recusa o formulário sem um link `meet.google.com`. O backend aceita reunião sem link: o campo é opcional na API e no banco.
- Todos os caminhos até a sala ao vivo passam pela agenda ou pela lista de reuniões.
- Com a spec 009, a sala captura qualquer fonte (WhatsApp, YouTube, tela inteira), mas o usuário precisa inventar um link do Meet para chegar até ela.

## Objetivo
Abrir uma sala de captura em um clique, sem Google Meet.

## Escopo
1. Botão "Nova captura" no menu lateral e no Dashboard.
2. Link do Meet opcional no modal "Nova reunião".
3. Identificação "Captura avulsa" nas listas de reuniões.
4. Backend: link em branco gravado como ausente (`null`).

## Fora de escopo
- Renomear a sessão dentro da sala (o título automático pode ser editado onde a edição já existir).
- Mudanças na agenda (Google Calendar).

## Requisitos funcionais
- **RF-01** "Nova captura" cria a reunião e abre `/meetings/{id}/live`, com:
  - título "Captura de dd/mm HH:mm";
  - descrição "Captura avulsa (sem Google Meet)";
  - `captureMechanism = BROWSER_CAPTURE`, `meetingType = GENERAL`, idioma pt-BR;
  - início agora, fim em +1 h.
- **RF-02** Enquanto cria, o botão fica desabilitado com "Abrindo…". Em caso de erro, mostra a mensagem e não navega.
- **RF-03** No modal "Nova reunião", o link é opcional. Se for informado, continua validado como `meet.google.com/...`.
- **RF-04** Nas listas (Dashboard e Reuniões), uma reunião sem link mostra "Captura avulsa".
- **RF-05** O backend grava link em branco (`""` ou espaços) como `null`.
- **RF-06** O item de menu "Reuniões Google Meet" passa a "Reuniões e capturas".

## Critérios de aceite
- **CA-01** Dado o clique em "Nova captura", então é feito `POST /meetings` sem `googleMeetLink`, com o título no formato do RF-01, e o app navega para a sala da reunião criada.
- **CA-02** Dada falha no `POST`, então aparece a mensagem de erro e não há navegação.
- **CA-03** Dado o modal sem link, então a reunião é criada; dado um link que não é do Meet, então aparece o erro de validação.
- **CA-04** Dada uma reunião sem link, então a lista mostra "Captura avulsa".
- **CA-05** Dado `googleMeetLink = "  "`, então a reunião é salva com link `null`.

## Compatibilidade
A API não muda. Reuniões existentes não mudam.
