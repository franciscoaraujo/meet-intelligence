# Especificações (SDD) — Meet Intelligence

Ponto de partida: [000-discovery/diagnostico.md](000-discovery/diagnostico.md).

| Spec | Tema | Prioridade | Status |
|---|---|---|---|
| [001](001-audio-capture/spec.md) | Captura de áudio (aba do Meet e microfone) | P0 / P2 | **Validada** (entrevista real em 2026-11-16) |
| [002](002-transcription-fallback/spec.md) | Fallback de transcrição | P0 | **Validada** (testes automatizados; entrevista real em 2026-11-16) |
| [003](003-speaker-identification/spec.md) | Identificação de falantes | P2 (base da 005) | **Validada** (entrevista real em 2026-11-16) |
| [004](004-ai-interview-copilot/spec.md) | Copiloto de entrevistas | P1 | **Validada** (entrevista real em 2026-11-16) |
| [005](005-question-detection/spec.md) | Detecção de perguntas e deduplicação | P1 | **Validada** (entrevista real em 2026-11-16) |
| [006](006-integration-testing/spec.md) | Testes automatizados e de integração | P0 (infraestrutura) | **Validada** (118 backend + 53 frontend; entrevista real em 2026-11-16) |
| 007 (proposta) | Segurança: WebSocket autenticado, isolamento entre organizações, chaves criptografadas | P1 | Não iniciada |
| [008](008-fluid-experience/spec.md) | Experiência fluida: copiloto em streaming, modo entrevista, UI incremental | P1 / P2 / P3 | **Validada** (146 backend + 78 frontend; entrevista real em 2026-11-16) |
| [009](009-any-audio-source/spec.md) | Captura de áudio de qualquer fonte (qualquer aba ou tela inteira com áudio do sistema) | P2 | **Validada** (146 backend + 84 frontend; entrevista real em 2026-11-16) |
| [010](010-quick-capture/spec.md) | Captura avulsa: "Nova captura" sem Google Meet | P2 | **Validada** (150 backend + 90 frontend; entrevista real em 2026-11-16) |
| [011](011-interview-language/spec.md) | Idioma da entrevista: português, inglês ou francês | P2 | **Validada** (163 backend + 97 frontend; entrevista real em 2026-11-16) |
| [012](012-card-translation/spec.md) | Botão "Traduzir" no card do copiloto | P2 | **Validada** (167 backend + 101 frontend; entrevista real em 2026-11-16) |
| [013](013-clean-live-ui/spec.md) | Interface limpa da sala ao vivo | P3 | **Validada** (105 frontend; entrevista real em 2026-11-16) |
| [014](014-answer-style/spec.md) | Estilo das respostas: padrão, informal, formal, técnica | P2 | **Validada** (170 backend + 108 frontend; entrevista real em 2026-11-16) |
| [015](015-question-context/spec.md) | Contexto da pergunta: o cenário dito antes acompanha a pergunta | P1 | **Validada** (180 backend + 114 frontend; entrevista real em 2026-11-16) |
| [016](016-multi-part-question/spec.md) | Pergunta em várias partes: o complemento entra no mesmo card | P1 | **Validada** (224 backend + 120 frontend; entrevista real em 2026-11-16) |
| [017](017-grok-transcription/spec.md) | Transcrição com o Grok: uma chave xAI para transcrição e respostas | P1 | **Implementada** (246 backend + 120 frontend; falta entrevista real) |

## Ordem de implementação proposta
Cada etapa só começa com a anterior verde (testes passando e critérios de aceite atendidos).

1. **Linha de base:** commitar as alterações pendentes da branch `feat/knowledge-base`.
2. **006 T-01..T-04:** infraestrutura de testes e correção do teste quebrado.
3. **002 (P0):** remover a transcrição falsa e corrigir a lista vazia, nessa ordem, sem separar; depois timeout/retry, idempotência e o novo contrato do `/chunk`.
4. **001 (P0/P2):** microfone pelo pipeline, eventos de encerramento, fila com retry, estado por fonte.
5. **003:** origem e papel do falante; fim da heurística.
6. **005:** detecção de perguntas, deduplicação, executor por reunião, correções do agregador.
7. **004:** copiloto dedicado.
8. **006 T-05..T-09:** integração ponta a ponta e validação em reunião real.

## Evoluções registradas (P3, sem implementação prevista)
- Diarização avançada de vários entrevistadores na aba.
- STT local com Faster-Whisper como **opção**, não como substituição.
- Streaming de áudio para reduzir latência (o streaming de tokens do copiloto passou para a spec 008).
- Processamento distribuído via RabbitMQ (já no compose) quando houver mais de uma instância.
- Escolha de modelo por custo e latência.

## Definição de pronto
Ver a seção 5 da missão. Resumo:
- Spec atualizada.
- Critérios de aceite atendidos.
- Testes verdes.
- Sem regressão conhecida.
- Logs e erros tratados.
- Documentação e limitações registradas.
