# Especificações (SDD) — Meet Intelligence

Ponto de partida: [000-discovery/diagnostico.md](000-discovery/diagnostico.md).

| Spec | Tema | Prioridade | Status |
|---|---|---|---|
| [001](001-audio-capture/spec.md) | Captura de áudio (aba do Meet e microfone) | P0 / P2 | **Implementada** (dublês; reunião real pendente) |
| [002](002-transcription-fallback/spec.md) | Fallback de transcrição | P0 | **Implementada** (testes automatizados; reunião real pendente) |
| [003](003-speaker-identification/spec.md) | Identificação de falantes | P2 (base da 005) | **Implementada** (testes; reunião real pendente) |
| [004](004-ai-interview-copilot/spec.md) | Copiloto de entrevistas | P1 | **Implementada** (testes; IA real pendente) |
| [005](005-question-detection/spec.md) | Detecção de perguntas e deduplicação | P1 | **Implementada** (backend; corpus a revisar) |
| [006](006-integration-testing/spec.md) | Testes automatizados e de integração | P0 (infraestrutura) | **Implementada** (118 backend + 53 frontend; reunião real pendente) |
| 007 (proposta) | Segurança: WebSocket autenticado, isolamento entre organizações, chaves criptografadas | P1 | Não iniciada |

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
- Streaming de áudio e de tokens para reduzir latência.
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
