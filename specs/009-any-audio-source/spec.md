# Spec 009 — Captura de áudio de qualquer fonte

Prioridade: **P2** · Status: **Implementada (testes automatizados; teste real pendente — roteiro, seção 7)** · Origem: pedido do usuário (capturar WhatsApp, YouTube e qualquer som do PC, não só o Meet)

## Contexto e problema
- A captura da "aba" (spec 001) usa `getDisplayMedia` sem restrição de origem. Ela já aceita qualquer aba e, no Chrome/Edge para Windows, a tela inteira com o áudio do sistema.
- A interface, porém, só fala do Meet:
  - O botão diz "Capturar Áudio da Guia Meet" e o indicador diz "Aba do Meet".
  - O falante remoto é "Participante remoto (Meet)".
  - A ajuda de "Sem áudio" manda marcar "Compartilhar áudio da guia" mesmo quando o usuário escolheu uma janela ou a tela inteira.
- Uma janela nunca entrega áudio, e o usuário não sabe disso.
- O áudio do sistema captura tudo o que o PC toca (notificações, músicas, vídeos), sem aviso.
- O aviso de consentimento só fala em LGPD de forma genérica, sem lembrar que ligações (WhatsApp etc.) exigem o consentimento do outro lado.

## Objetivo
Deixar claro e confiável o uso de qualquer fonte de áudio do computador (aba de qualquer site, tela inteira com áudio do sistema), com orientação certa para cada escolha, sem mudar o pipeline de transcrição.

## Escopo
1. Textos genéricos: botão, indicador da fonte, rótulo do falante remoto e aviso de consentimento.
2. Pedir ao navegador o áudio do sistema e esconder a própria aba do app do seletor.
3. Identificar a superfície escolhida (aba, tela inteira, janela) e mostrá-la no indicador.
4. Ajuda de "Sem áudio" específica por superfície.
5. Aviso de ruído quando a fonte é a tela inteira.

## Fora de escopo
- Usar a imagem da tela (leitura de conteúdo, OCR, envio ao copiloto).
- Áudio do sistema no macOS/Linux além do que o navegador oferece.
- Separar vários falantes dentro do áudio do computador (continua um único "Participante remoto").
- Mudança de API ou de banco. Os segmentos antigos mantêm o rótulo gravado ("Participante remoto (Meet)").

## Requisitos funcionais
- **RF-01** O botão passa a "Capturar áudio do computador" / "Parar captura do computador". A dica explica as duas formas: aba (marcando "Compartilhar áudio da guia") ou tela inteira (marcando "Compartilhar áudio do sistema").
- **RF-02** O pedido ao navegador inclui:
  - `systemAudio: 'include'`;
  - `selfBrowserSurface: 'exclude'`, para o usuário não capturar a própria sala e gerar eco;
  - `surfaceSwitching: 'include'`.
  Navegadores que ignoram essas opções continuam funcionando como hoje.
- **RF-03** A superfície (`browser`, `monitor`, `window`) vem de `getSettings().displaySurface` da trilha de vídeo. O indicador mostra "Áudio do computador (aba)", "(tela inteira)" ou "(janela)".
- **RF-04** "Sem áudio" orienta conforme a superfície:
  - janela → janelas não compartilham áudio; escolha uma aba ou a tela inteira;
  - aba → marque "Compartilhar áudio da guia";
  - tela inteira → marque "Compartilhar áudio do sistema" (Windows/ChromeOS) ou use uma aba no macOS/Linux.
- **RF-05** Com a tela inteira ativa, aparece um aviso dispensável: "Todo o som do computador está sendo capturado: notificações, músicas e vídeos também serão transcritos."
- **RF-06** O rótulo do falante da fonte do computador passa a "Participante remoto", no backend (`SpeakerAttributionService.REMOTE_LABEL`) e no frontend.
- **RF-07** O aviso de consentimento passa a dizer que a gravação e a análise exigem o consentimento de todos os participantes da reunião ou ligação (LGPD).

## Regras de negócio
- **RN-01** Nada muda no caminho do áudio: VAD, envio, transcrição, falantes e perguntas continuam iguais (specs 001-005).
- **RN-02** A fonte continua sendo `TAB` no contrato (`audioSource`). O nome técnico não muda para não quebrar a API nem os dados gravados.

## Critérios de aceite
- **CA-01** Dado o pedido de captura, então o `getDisplayMedia` recebe `systemAudio: 'include'` e `selfBrowserSurface: 'exclude'`.
- **CA-02** Dada uma janela sem áudio, então o estado é "Sem áudio" com a orientação de janela.
- **CA-03** Dada a tela inteira com áudio, então a fonte fica ativa, o indicador mostra "(tela inteira)" e o aviso de ruído aparece.
- **CA-04** Dada a tela inteira sem áudio, então a orientação cita "Compartilhar áudio do sistema".
- **CA-05** Dado um segmento novo da fonte do computador, então o rótulo é "Participante remoto".

## Segurança e privacidade
- Esconder a própria aba do seletor evita capturar a sala do app (eco e vazamento de respostas do copiloto para a transcrição).
- O aviso de consentimento cobre ligações de terceiros (WhatsApp etc.).

## Compatibilidade
- A API não muda. Segmentos antigos mantêm o rótulo antigo.
- Os testes que verificavam "Participante remoto (Meet)" passam a verificar o rótulo novo.
