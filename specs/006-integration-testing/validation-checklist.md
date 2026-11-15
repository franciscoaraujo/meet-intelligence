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
| 4.10 | Medir o tempo entre o fim da pergunta e o card (cronômetro) | Registrar p50/p90 no relatório (alvo: p50 ≤ 6 s, p90 ≤ 12 s) | |

## 5. Encerramento
| # | Passo | Resultado esperado | OK? |
|---|---|---|---|
| 5.1 | "Encerrar Captura" no meio de uma fala | A fala em andamento é enviada; ícone de gravação do Chrome some (nenhuma trilha ativa) | |
| 5.2 | Recarregar a página | Transcrição, perguntas e cards voltam na ordem certa | |

Registrar os resultados em `validation-report.md` (seção "Validação em reunião real"), com data, navegador, provedores usados e observações.
