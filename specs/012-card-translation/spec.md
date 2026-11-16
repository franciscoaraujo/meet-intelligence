# Spec 012 — Botão "Traduzir" no card do copiloto

Prioridade: **P2** · Status: **Implementada (2026-11-15; testes automatizados)** · Origem: pedido do usuário durante uma entrevista em inglês (spec 011): "coloque um botão de traduzir os textos desse card"

## Objetivo
Ler o card do copiloto (pergunta, resposta falada e seções) em outro idioma, sobretudo em português numa entrevista em inglês ou francês, e voltar ao original.

## Requisitos funcionais
- **RF-01** `POST /api/v1/meetings/{meetingId}/questions/{questionId}/translate?language=pt-BR|en-US|fr-FR` devolve `{ language, question, spokenAnswer, sections }`.
- **RF-02** A tradução usa o provedor de IA da organização, com o modelo do copiloto quando configurado (spec 008), e o fallback do orquestrador.
- **RF-03** A primeira tradução por idioma é gravada nos metadados do card (`translations.<idioma>`); as próximas não chamam a IA. Gerar a resposta de novo descarta as traduções.
- **RF-04** O botão "Traduzir" no cabeçalho do card abre Português, English e Français.
  - Durante a tradução, aparece "Traduzindo…".
  - Depois, título, resposta falada e seções aparecem traduzidos, com o selo do idioma e "Ver original".
  - "Copiar" copia o texto exibido.
- **RF-05** Se a tradução falhar, aparece "Não foi possível traduzir agora" e o original continua.
- **RF-06** O botão só aparece em cards com resposta concluída (não no rascunho em streaming).

## Regras de negócio
- **RN-01** A tradução não acrescenta nem remove fatos; empresas, projetos, tecnologias, siglas e números ficam iguais.
- **RN-02** Continua em primeira pessoa e em linguagem falada.
- **RN-03** Só o texto do card vai para o provedor: nem transcrição nem currículo.

## Critérios de aceite
- **CA-01** Card respondido + português → tradução devolvida e gravada nos metadados.
- **CA-02** Segundo pedido no mesmo idioma → a IA não é chamada.
- **CA-03** Pergunta sem resposta → 404; idioma inválido → 400.
- **CA-04** Resposta da IA inválida ou IA indisponível → 503, nada gravado.
- **CA-05** No card: escolher Português mostra o texto traduzido com o selo; "Ver original" volta; falha mostra a mensagem e mantém o original; traduções já gravadas aparecem sem nova chamada.

## Implementação
| Peça | Detalhe |
|---|---|
| `AnswerTranslationService` | Lê `copilotAnswer` dos metadados do card, chama `AIOrchestrator.execute(..., ANSWER_TRANSLATION)` (modelo do copiloto), valida o JSON e grava em `translations.<idioma>` |
| `QuestionController` | `POST /{questionId}/translate?language=`; 503 com mensagem quando a IA falha |
| `InterviewAnswerCard` | Botão "Traduzir" no cabeçalho, menu Português/English/Français, selo "Traduzido: …", "Ver original"; usa as traduções já gravadas |
| Testes | `AnswerTranslationServiceTest` (4), `InterviewAnswerCard.test.tsx` (+4). Backend 167, frontend 101 |

