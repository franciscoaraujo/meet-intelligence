# Roteiro de validação em reunião real (spec 006, T-08)

Objetivo: confirmar, com áudio, navegador e provedores reais, o que os testes automatizados só verificaram com dublês e mocks. Leva cerca de 30 minutos e precisa de duas máquinas (ou um celular) para fazer o papel do entrevistador.

## Pré-requisitos
- [ ] Backend e frontend rodando com a branch atual; o log mostra as migrations V7, V8 e V9 aplicadas.
- [ ] Pelo menos **um provedor de transcrição** com chave válida: Gemini da organização (Configurações), ou `DEEPGRAM_API_KEY`/`OPENAI_API_KEY` no servidor.
- [ ] Pelo menos **um provedor de IA** com chave válida em Configurações (Gemini, Grok ou Claude), salvo e com "Testar conexão" verde.
- [ ] Currículo enviado na Base de Conhecimento.
- [ ] Chrome atualizado. Uma reunião do Meet com outra pessoa (ou outro dispositivo) como entrevistador.

## 1. Captura (spec 001)
| # | Passo | Resultado esperado | OK? |
|---|---|---|---|
| 1.1 | Na sala ao vivo, "Iniciar Captura" e **negar** a permissão do microfone | Indicador "Seu microfone: Permissão negada" com instrução; sem `alert()` | |
| 1.2 | Liberar o microfone no cadeado e iniciar de novo | "Ativa (silêncio)"; o VU mexe ao falar | |
| 1.3 | "Capturar Áudio da Guia Meet" e escolher a aba **sem** marcar "Compartilhar áudio da guia" | "Aba do Meet: Sem áudio" com instrução | |
| 1.4 | Repetir marcando o áudio da guia | "Aba do Meet: Ativa"; o VU mexe quando o entrevistador fala | |
| 1.5 | No Chrome, clicar em "Parar compartilhamento" | "Interrompida" + botão "Compartilhar aba novamente"; o microfone continua ativo | |
| 1.6 | Desconectar o fone/microfone USB durante a captura | "Reconectando…" e depois volta a ficar ativo, ou "Dispositivo indisponível" após 3 tentativas | |
| 1.7 | DevTools → Network → Offline por 10 s enquanto o entrevistador fala; voltar a Online | "reenviando…"; a fala aparece depois **uma única vez** | |
| 1.8 | Sem fone de ouvido, entrevistador fala alto | Aviso de fone de ouvido aparece; falas repetidas do microfone não geram pergunta | |

## 2. Transcrição e fallback (spec 002)
| # | Passo | Resultado esperado | OK? |
|---|---|---|---|
| 2.1 | Entrevistador fala uma frase | Aparece na transcrição com ícone de aba e "Participante remoto (Meet)" | |
| 2.2 | Colocar uma chave **inválida** no Gemini da organização (mantendo outro provedor válido) | A fala continua sendo transcrita pelo outro provedor (campo `source` do segmento / log `STT GEMINI falhou`) | |
| 2.3 | Remover todos os provedores de STT | Indicador da fonte mostra "Nenhum serviço de transcrição configurado…"; nenhuma fala inventada aparece | |
| 2.4 | Silêncio prolongado | Nenhum segmento vazio aparece | |

## 3. Falantes (spec 003)
| # | Passo | Resultado esperado | OK? |
|---|---|---|---|
| 3.1 | Você fala no microfone | Segmento com ícone de microfone e o seu nome (do login) | |
| 3.2 | Entrevistador e você falam ao mesmo tempo | Dois segmentos, um por origem; o seu **não** aparece como entrevistador | |
| 3.3 | Corrigir o falante de um segmento (se exposto na UI) / texto digitado como "Entrevistador" | Rótulo aplicado | |

## 4. Perguntas e copiloto (specs 005 e 004)
| # | Passo | Resultado esperado | OK? |
|---|---|---|---|
| 4.1 | Entrevistador: "Explique o conceito de SOLID" | Em poucos segundos: card "Copiloto" com versão para falar e seções | |
| 4.2 | Entrevistador: "aqui a gente usa Kafka e Spring" | **Nenhum** card de resposta | |
| 4.3 | Entrevistador: "me fale sobre sua experiência com mensageria" (sem "?") | Card com resposta em primeira pessoa usando **só** fatos do currículo | |
| 4.4 | Entrevistador repete a mesma pergunta 30 s depois | Continua **um** card | |
| 4.5 | Duas perguntas seguidas com pausa de ~3 s | **Dois** cards separados | |
| 4.6 | Você começa a responder logo após a pergunta | O card aparece mesmo assim (a sua fala fecha a pergunta) | |
| 4.7 | "Gerar de novo" num card | O mesmo card é atualizado | |
| 4.8 | "Não era pergunta" | O card some | |
| 4.9 | Pergunta sobre tecnologia que **não** está no currículo | A resposta é conceitual, sem afirmar experiência sua | |
| 4.11 | Entrevistador faz um comentário e depois uma pergunta; clicar **"Pergunta terminou"** | Card com o título igual à pergunta (sem o comentário) e a resposta; nunca some como "não era pergunta" | |
| 4.10 | Medir o tempo entre o fim da pergunta e o card (cronômetro) | Registrar p50/p90 no relatório (alvo: p50 ≤ 6 s, p90 ≤ 12 s) | |

## 5. Encerramento
| # | Passo | Resultado esperado | OK? |
|---|---|---|---|
| 5.1 | "Encerrar Captura" no meio de uma fala | A fala em andamento é enviada; ícone de gravação do Chrome some (nenhuma trilha ativa) | |
| 5.2 | Recarregar a página | Transcrição, perguntas e cards voltam na ordem certa | |

## 6. Experiência fluida (spec 008)
Funciona mesmo sem transcrição configurada: no campo de texto da sala, escolha "Entrevistador" e digite a pergunta.

| # | Passo | Resultado esperado | OK? |
|---|---|---|---|
| 6.1 | Entrevistador: "Explique o conceito de SOLID" | O card aparece com esqueleto e "Gerando resposta…"; em seguida o texto cresce aos poucos com o selo **Rascunho** | |
| 6.2 | Esperar o fim da geração | O selo some, aparecem "Detalhes da resposta" e os botões Copiar/Gerar de novo | |
| 6.3 | Cronometrar o tempo da pergunta até a primeira palavra do card, em 10 perguntas | Registrar p50/p90 (alvo: p50 ≤ 1,5 s, p90 ≤ 3 s). Conferir com `copilot.ttft` em `/actuator/metrics/copilot.ttft` | |
| 6.4 | Cronometrar até o fim da resposta falada | Registrar p50/p90 (alvo: p50 ≤ 5 s, p90 ≤ 10 s) | |
| 6.5 | "Gerar de novo" num card respondido | O texto antigo é trocado pelo novo rascunho, que cresce aos poucos | |
| 6.6 | Botão "Modo entrevista" no topo | Pergunta atual e resposta em destaque, histórico ao lado, transcrição com as últimas 3 falas; recarregar a página mantém a escolha | |
| 6.6b | Entrar no modo entrevista com a captura rodando | O indicador "Seu microfone" some e só o áudio do computador continua; sair do modo religa o microfone | |
| 6.7 | Duas perguntas seguidas no modo entrevista | A segunda vai para o destaque; clicar na primeira no histórico mostra o card dela | |
| 6.8 | Com várias falas, rolar a transcrição para cima e deixar o entrevistador falar | A posição não muda; aparece "N novas falas ↓" | |
| 6.9 | Falar no microfone (modo Web Speech) | "Você (falando…)" com o texto provisório em cinza, substituído pela fala final | |
| 6.10 | Entrevistador fala pela aba do Meet | "Participante remoto falando…" com o nível de áudio, até a fala transcrita aparecer | |
| 6.11 | Opcional: `GROK_COPILOT_MODEL=grok-4.20-0309-non-reasoning` no servidor e repetir 6.3 | Comparar o TTFT com o modelo padrão e registrar | |

## 7. Áudio de qualquer fonte (spec 009)
| # | Passo | Resultado esperado | OK? |
|---|---|---|---|
| 7.0 | Menu lateral → **"Nova captura"** (spec 010) | Abre a sala ao vivo de uma sessão "Captura de dd/mm HH:mm", sem pedir link do Meet; em Reuniões ela aparece como "Captura avulsa" | |
| 7.1 | "Capturar áudio do computador" → aba do **YouTube** com "Compartilhar áudio da guia" | "Áudio do computador (aba): Ativa"; a fala do vídeo aparece como "Participante remoto" | |
| 7.2 | Repetir com uma **chamada no WhatsApp Web** (aba) | A voz da outra pessoa é transcrita; perguntas geram card do copiloto | |
| 7.3 | "Tela inteira" + "Compartilhar áudio do sistema" (Windows) com o **app WhatsApp Desktop** ou Teams/Zoom | "Áudio do computador (tela inteira)"; aviso "Todo o som do computador está sendo capturado…" | |
| 7.4 | Escolher uma **janela** | "Sem áudio" com a orientação "Janelas não compartilham áudio…" | |
| 7.5 | Tela inteira **sem** marcar o áudio do sistema | "Sem áudio" citando "Compartilhar áudio do sistema" | |
| 7.6 | Abrir o seletor | A aba do próprio Meet Intelligence não aparece como opção (Chrome) | |

## 8. Idioma da entrevista (spec 011)
| # | Passo | Resultado esperado | OK? |
|---|---|---|---|
| 8.1 | Na sala, Idioma → **English**; entrevistador pergunta em inglês ("how would you design a rate limiter") | Fala transcrita em inglês; card do copiloto com a resposta e as seções em inglês | |
| 8.2 | Comentário seguido de pergunta em inglês ("great answer, now how would you monitor it") | Pergunta detectada sozinha | |
| 8.3 | Repetir 8.1-8.2 em **Français** | Transcrição e resposta em francês | |
| 8.4 | Voltar para Português no meio da captura | Próximas falas e respostas em português | |
| 8.5 | Clicar "Nova captura" depois de escolher Français | A nova sessão já abre em francês | |
| 8.5b | Num card em inglês, **Traduzir → Português** (spec 012) | Pergunta, resposta e seções em português, selo "Traduzido"; "Ver original" volta; pedir de novo é instantâneo | |
| 8.6 | Observação: o STT local do Google tem qualidade menor em inglês/francês com sotaque; com a chave do Gemini a transcrição tende a ser melhor | Registrar | |

Registrar os resultados em `validation-report.md` (seção "Validação em reunião real"), com data, navegador, provedores usados e observações.
