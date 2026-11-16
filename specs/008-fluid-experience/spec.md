# Spec 008 — Experiência fluida

Prioridade: **P1** (copiloto em streaming) / **P2** (modo entrevista, UI incremental) / **P3** (texto provisório, divisão do bundle) · Status: **Implementada (testes automatizados; medição com IA real pendente — CA-12)** · Origem: pedido do usuário ("fluido como o NotebookLM") e item "Streaming de tokens" das evoluções P3 do [README](../README.md)

## Contexto e problema
- **Espera sem retorno visual:** o copiloto (spec 004) só mostra a resposta depois que o LLM devolve o JSON inteiro e o validador aprova. Com Grok, o teste real levou cerca de 4,5 s numa chamada curta. Respostas completas podem levar de 6 a 12 s. Nesse tempo a tela não mostra nada.
- **Nova tentativa invisível:** quando o validador rejeita a resposta, o serviço tenta de novo e o tempo dobra, ainda sem retorno visual.
- **Tela que pula:**
  - O polling de 30 s e a ressincronização após reconexão do WebSocket substituem as listas inteiras.
  - A rolagem automática acontece mesmo quando o usuário está lendo um trecho anterior.
  - Não há esqueleto de carregamento nem transição.
- **Excesso de informação durante a entrevista:** transcrição, insights, perguntas e cards dividem a tela com o mesmo peso. O que importa na hora (a última pergunta e a resposta sugerida) não se destaca.
- **Fala própria só aparece depois:** o microfone usa Web Speech com `interimResults = true`, mas o texto provisório não é mostrado. A fala só aparece quando o segmento final chega do backend.
- **Primeira carga pesada:** o bundle do frontend tem um único arquivo JS de ~790 KB, e o Vite já avisa que passa de 500 KB.

## Objetivo
Fazer a resposta do copiloto começar a aparecer em até ~1,5 s depois que a pergunta é detectada e crescer palavra por palavra. A tela deve mudar só no que mudou, sem pulos, e oferecer um modo focado para a entrevista. Tudo isso sem perder as garantias da spec 004: fidelidade ao currículo e validação.

## Escopo
1. **Copiloto em streaming:**
   - Os provedores Grok, Claude e Gemini enviam o texto por partes.
   - O backend repassa a `spoken_answer` parcial por WebSocket.
   - O validador continua rodando no fim.
2. **Modelo do copiloto configurável:** um modelo por provedor só para o copiloto, separado do modelo da análise incremental.
3. **UI incremental:** mesclagem por id em vez de substituição, rolagem automática só quando o usuário está no fim, esqueletos e transições.
4. **Modo entrevista:** um layout focado na última pergunta e na resposta, com a transcrição reduzida.
5. **Texto provisório do microfone:** a fala do usuário aparece em cinza enquanto ele fala.
6. **Divisão do bundle:** rotas carregadas sob demanda.

## Fora de escopo
- STT em streaming para o áudio da aba (Deepgram live ou equivalente). O áudio da aba continua por segmentos de VAD (spec 001). Só ganha o indicador "ouvindo…".
- Fechar enunciados mais cedo na detecção de perguntas (spec 005). Os parâmetros de pausa não mudam.
- Integração com o NotebookLM.
- Escolha do modelo do copiloto pela UI de Configurações por organização. Nesta spec o modelo é configurado no servidor.
- Segurança do tópico WebSocket (spec 007).

## Requisitos funcionais
### Copiloto em streaming
- **RF-01** Os três provedores de LLM oferecem uma operação de streaming que entrega o texto em partes (deltas), na ordem, e informa o fim e o uso de tokens.
  - Um provedor sem streaming continua funcionando: ele entrega um único delta com o texto inteiro.
- **RF-02** O prompt do copiloto passa a exigir que `spoken_answer` venha logo depois de `is_question_for_candidate` (que continua primeiro, para o modelo confirmar a pergunta antes de responder).
  - O backend extrai incrementalmente o valor desse campo, já sem as sequências de escape do JSON, e só repassa esse texto. Chaves e estrutura do JSON nunca chegam à UI.
- **RF-03** Eventos novos no tópico `/topic/meetings/{id}/copilot` (contrato no design):
  - `STARTED`: pergunta e tentativa.
  - `DELTA`: texto acumulado ou incremento com número de sequência.
  - `COMPLETED`: resposta validada, com o mesmo conteúdo publicado hoje em `/insights`.
  - `REJECTED`: a tentativa foi descartada pelo validador; uma nova tentativa virá ou a pergunta falhará.
  - `FAILED`: erro do provedor.
- **RF-04** Enquanto chegam deltas, o card mostra "rascunho". Só depois do `COMPLETED` mostra as seções (`direct_answer`, `technical_explanation` etc.) e o selo de validado.
- **RF-05** Se o validador rejeitar a tentativa:
  - O texto em rascunho é **substituído**, não somado, pela próxima tentativa.
  - O card mostra "Ajustando a resposta…".
  - Se todas as tentativas falharem, o rascunho é removido e o card mostra o mesmo estado de falha da spec 004.
- **RF-06** Fallback entre provedores (`AIOrchestrator`):
  - Se o provedor falhar **antes do primeiro delta**, o fallback acontece como hoje, sem evento para a UI além do atraso.
  - Se falhar **depois** do primeiro delta, a tentativa conta como falha e segue a regra de nova tentativa do RF-05.
- **RF-07** "Gerar de novo" e "Responder última pergunta" também usam streaming.
- **RF-08** Ao recarregar a página ou reconectar no meio de um streaming, a UI mostra a resposta já concluída (REST, como hoje) ou o estado "gerando…". Um rascunho parcial não precisa ser recuperado.

### Modelo do copiloto
- **RF-09** Propriedades `app.ai.<provedor>.copilot-model` (variáveis `GROK_COPILOT_MODEL`, `CLAUDE_COPILOT_MODEL`, `GEMINI_COPILOT_MODEL`).
  - Sem valor, vale o modelo atual do provedor (comportamento de hoje).
  - O modelo usado vai nos metadados do insight (já existe o campo `model`).

### UI incremental
- **RF-10** O polling e a ressincronização **mesclam por id** transcrição, insights e perguntas:
  - Um item existente só é re-renderizado se mudou.
  - A ordem cronológica é mantida (da mais antiga para a mais nova).
- **RF-11** A rolagem automática da transcrição só acontece se o usuário estiver a até ~80 px do fim. Caso contrário, aparece o botão "N novas falas ↓".
- **RF-12** Itens novos entram com transição curta (≤ 200 ms). Com `prefers-reduced-motion`, não há animação.
- **RF-13** O carregamento inicial da sala mostra esqueletos no lugar de "Carregando…" e listas vazias.

### Modo entrevista
- **RF-14** Um botão "Modo entrevista" na sala ao vivo alterna o layout:
  - A última pergunta detectada e o card do copiloto ficam em destaque, com fonte maior para a `spoken_answer`.
  - A transcrição fica reduzida às últimas 3 falas, com opção de expandir.
  - O histórico de perguntas fica em lista compacta, e clicar abre o card.
  - Insights da análise incremental ficam ocultos e acessíveis por aba.
- **RF-15** A escolha do modo fica salva no navegador (`localStorage`). O modo vem ativado por padrão quando o papel da reunião é "candidato".
- **RF-20** (adicionado em 2026-11-15, pedido do usuário) No modo entrevista só o participante remoto é capturado:
  - Entrar no modo desliga o microfone, inclusive com a captura em andamento, sem parar o áudio do computador.
  - Sair do modo volta o microfone para "transcrição do servidor".
  - O seletor ganha a opção "Microfone: desligado (só o participante remoto)" e pode ser trocado durante a captura.
  - **Revisto às 22:25, após uso real:** no modo entrevista o microfone fica **bloqueado** na camada de captura (`micBlocked`): não liga em nenhum modo, o seletor fica travado em "desligado no modo entrevista" e não há como religá-lo sem sair do modo.
  - Sem a fala do candidato, a pergunta é fechada pela pausa (spec 005), não pela resposta dele.
- **RF-16** Quando uma nova pergunta é detectada no modo entrevista, ela passa a ser a pergunta em destaque. A anterior vai para o histórico, sem perder o card.

### Texto provisório
- **RF-17** O texto provisório do Web Speech aparece no fim da transcrição em cinza/itálico, com o ícone de microfone.
  - É substituído quando o segmento final chega.
  - Nunca é enviado ao backend como segmento.
- **RF-18** Enquanto o VAD da aba tem um segmento aberto, a transcrição mostra "Participante remoto falando…" com o VU.

### Bundle
- **RF-19** As rotas são carregadas sob demanda (`React.lazy`). Bibliotecas grandes ficam em chunks separados.

## Requisitos não funcionais
- **RNF-01 Tempo até o primeiro texto (TTFT):** do evento `DETECTED` até o primeiro `DELTA` exibido:
  - p50 ≤ 1,5 s e p90 ≤ 3 s com o modelo do copiloto configurado.
  - **Só vale medido com chave real.** Testes com dublês verificam ordem e contrato, não latência.
- **RNF-02 Resposta falada completa:** p50 ≤ 5 s e p90 ≤ 10 s, também medidos com chave real.
- **RNF-03 Taxa de eventos:** os deltas enviados ao WebSocket são agrupados: no máximo 1 evento a cada 100 ms por pergunta, sempre enviando o resto no fim.
- **RNF-04 Sem dependência nova no backend:** o streaming HTTP usa `java.net.http.HttpClient` do JDK 21.
- **RNF-05 Bundle:** nenhum chunk acima de 500 KB (sem aviso do Vite), e a página de login não carrega o código da sala ao vivo.
- **RNF-06 Observabilidade:** métricas `copilot.ttft` e `copilot.latency` (já existe) por provedor e modelo; contador `copilot.stream.fallback`.

## Regras de negócio
- **RN-01** A fidelidade ao currículo vale para o resultado final. O rascunho pode ser descartado e **nunca** aparece como validado.
- **RN-02** A UI nunca mostra JSON cru, nem o conteúdo de uma tentativa rejeitada depois do `REJECTED`.
- **RN-03** A simulação sem chave (spec 004) também usa os eventos de streaming: ela emite o texto simulado em partes, para a UI ter um caminho só.
- **RN-04** O streaming não muda quem decide se é pergunta (spec 005), nem o JSON gravado no banco.

## Fluxos
### Principal
1. A spec 005 marca a pergunta como `DETECTED`.
2. O copiloto publica `STARTED`. O card aparece com esqueleto e "gerando…".
3. O provedor envia deltas. O extrator libera o texto de `spoken_answer`, e o card cresce palavra por palavra.
4. O JSON termina e o validador aprova.
5. O backend grava o insight e publica `COMPLETED`, mais o evento atual em `/insights`. O card troca o rascunho pelo conteúdo validado com as seções.

### Rejeição
- O passo 4 reprova.
- O backend publica `REJECTED`, e o card mostra "Ajustando a resposta…" e limpa o rascunho.
- Uma nova tentativa recomeça no passo 2, com `attempt` maior.

### Fallback
- Grok falha (timeout, 5xx) antes do primeiro delta.
- O orquestrador tenta o Gemini em streaming. A UI só nota o atraso.

## Cenários de erro
| Cenário | Comportamento |
|---|---|
| Provedor fecha a conexão no meio do streaming | Tentativa falha → `REJECTED` com motivo `STREAM_ABORTED` → nova tentativa ou `FAILED` |
| Delta com escape JSON partido entre dois pacotes (`\` no fim) | O extrator guarda o resto e só libera o caractere completo |
| Modelo não começa pelo campo `spoken_answer` | Nenhum delta é liberado; a resposta chega inteira no `COMPLETED` (degrada para o comportamento de hoje) e o contador `copilot.stream.no_spoken_first` sobe |
| WebSocket desconectado durante o streaming | Ao reconectar, a UI busca o estado por REST; se `ANSWERED`, mostra a resposta final |
| Duas perguntas em streaming ao mesmo tempo | Eventos separados por `questionId`; cada card cresce de forma independente |
| Evento `DELTA` fora de ordem ou repetido | A UI ignora `seq` menor ou igual ao último aplicado |
| Modelo do copiloto configurado inexistente | Erro 4xx do provedor antes do primeiro delta → fallback (RF-06) e log de aviso com o nome do modelo |

## Critérios de aceite
- **CA-01** Dado um provedor fake que envia o JSON em 20 partes, quando a pergunta é detectada, então a UI recebe `STARTED`, deltas cujo texto concatenado é exatamente a `spoken_answer` final, e um `COMPLETED`, nessa ordem e sem JSON cru.
- **CA-02** Dado um provedor fake cuja primeira tentativa inventa experiência, quando ela termina, então:
  - Sai `REJECTED`, e o rascunho some da UI.
  - A segunda tentativa recomeça do zero.
  - Só a segunda é gravada.
- **CA-03** Dado Grok falhando antes do primeiro delta e Gemini funcionando, quando a pergunta é detectada, então a resposta chega por streaming do Gemini e `copilot.stream.fallback` sobe.
- **CA-04** Dado um provedor que cai depois de 5 deltas, quando isso acontece, então a tentativa é descartada (`REJECTED`/`STREAM_ABORTED`) e nada é gravado dessa tentativa.
- **CA-05** Dado um JSON com escapes (`\"`, `\n`, `ç`) divididos entre pacotes, quando extraído, então o texto liberado é idêntico ao valor decodificado do campo.
- **CA-06** Dado um provedor que entrega 200 deltas em 1 s, quando repassados, então chegam ao WebSocket no máximo ~11 eventos `DELTA`, sem perda de texto.
- **CA-07** Dada uma lista de 50 falas e um polling que devolve as mesmas 50 mais 1, quando mesclada, então só a fala nova é montada no DOM (teste de renderização).
- **CA-08** Dado o usuário rolado para cima na transcrição, quando chega uma fala nova, então a posição não muda e aparece "1 nova fala ↓".
- **CA-09** Dado o modo entrevista ativo, quando uma segunda pergunta é detectada, então ela fica em destaque e a primeira continua acessível no histórico com o seu card.
- **CA-10** Dado o Web Speech emitindo texto provisório, quando o resultado final chega, então o provisório é removido e nenhum segmento provisório é enviado ao backend.
- **CA-11** Dado `npm run build`, então nenhum chunk passa de 500 KB e a rota de login não importa o chunk da sala ao vivo.
- **CA-12 (real)** Dado o Grok com chave real e o modelo do copiloto configurado, quando o roteiro da spec 006 (item 4.10) é executado com pelo menos 10 perguntas, então TTFT p50 ≤ 1,5 s e resposta falada p50 ≤ 5 s são registrados no relatório. Se a meta não for atingida, os números reais ficam registrados e a spec continua aberta.

## Segurança e privacidade
- O tópico novo segue o mesmo modelo dos tópicos `/insights` e `/questions`. O risco de WebSocket sem autenticação (S-01) não aumenta nem diminui; ele é tratado na spec 007.
- Os deltas não são registrados em log; só tamanhos, tempos e o id da pergunta.
- As chaves continuam só no servidor e mascaradas na UI.

## Compatibilidade
- Os eventos atuais de `/insights` e `/questions` continuam iguais e são publicados no fim, como hoje. Um cliente antigo sem o tópico novo vê o mesmo comportamento de hoje.
- O JSON gravado no insight não muda.
- A ordem dos campos no prompt muda (`spoken_answer` primeiro). O marcador passa a `INTERVIEW_COPILOT_V2` no arquivo `prompts/interview-answer.v2.md`. A v1 fica no repositório para comparação.
