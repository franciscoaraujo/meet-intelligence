# Spec 021 — Busca no histórico de entrevistas e prazo de guarda

Prioridade: **P2** · Status: **Planejada** · Origem: pedido do usuário (2026-11-16), "persistir as transcrições para criar um histórico"; continuação da spec 020

## Problema
As transcrições, perguntas e respostas ficam gravadas, mas o histórico só é navegável reunião por reunião. Não dá para responder perguntas como:
- "Como eu respondi sobre Kafka nas últimas entrevistas?"
- "Que perguntas de arquitetura já me fizeram?"

No SaaS, guardar a voz e a fala de terceiros (os entrevistadores) por tempo indeterminado também não atende à LGPD: falta um prazo de guarda definido e aplicado.

## Objetivo
Encontrar em segundos qualquer pergunta, resposta ou trecho já dito, em todas as entrevistas da organização. Os dados ficam guardados pelo tempo definido e depois são apagados automaticamente, com aviso.

## Requisitos funcionais

### Busca
- **RF-01 Endpoint:** `GET /api/v1/history/search?q=<texto>&type=<QUESTION|ANSWER|TRANSCRIPT>&from=&to=&page=`:
  - busca só na organização do usuário (spec 007);
  - inclui reuniões arquivadas, que a spec 020 mantém;
  - resultados paginados, dos mais recentes para os mais antigos.
- **RF-02 O que é buscado:**
  - texto das perguntas detectadas, incluindo o contexto (spec 015) e os complementos (spec 016);
  - texto das respostas do copiloto;
  - trechos de transcrição (o texto corrigido quando houver).
- **RF-03 Busca textual em português, inglês e francês:**
  - PostgreSQL full-text (`tsvector`), com o dicionário do idioma da reunião (spec 011) e sem acentos (`unaccent`);
  - índices GIN criados por migration;
  - a busca semântica (pgvector) fica fora do escopo.
- **RF-04 Resultado:** cada item traz:
  - o tipo, a reunião (título e data) e o horário na captura;
  - um trecho com o termo destacado;
  - o link para abrir a sala da reunião já posicionada no trecho, pergunta ou card.
- **RF-05 Tela "Histórico":** nova página com:
  - campo de busca e filtros de tipo e período;
  - a lista de resultados (RF-04);
  - sem busca, as últimas perguntas recebidas, das mais recentes para as mais antigas.

### Prazo de guarda (LGPD)
- **RF-06 Configuração:** `organizations.retention_days`:
  - padrão **365**; `null` = sem limite, só para planos que o permitam;
  - editável por `OWNER`/`ADMIN` em Configurações.
- **RF-07 Limpeza automática:**
  - uma tarefa diária apaga, de forma definitiva, as reuniões concluídas há mais de `retention_days`, com tudo o que depende delas (a mesma cascata da exclusão definitiva da spec 020);
  - `llm_usage` fica, como já acontece, sem o vínculo com a reunião, para a cobrança.
- **RF-08 Aviso antes de apagar:**
  - reuniões que vencem nos próximos 7 dias aparecem com o selo "Será apagada em N dias" na lista e no histórico;
  - a ação "Manter por mais tempo" (`OWNER`/`ADMIN`) adia o prazo daquela reunião (`meetings.retain_until`).
- **RF-09 Exportar antes de apagar:** a exportação existente (ata em TXT/DOCX/PDF) passa a incluir a transcrição completa e as perguntas e respostas, para o usuário guardar fora do sistema.
- **RF-10 Registro:** cada limpeza registra no log quantas reuniões e de quais organizações foram apagadas, só com identificadores e sem conteúdo.

## Regras de negócio
- **RN-01** A busca nunca devolve dados de outra organização (testes no padrão do `SecurityHardeningIT`).
- **RN-02** A limpeza só apaga reuniões `COMPLETED` ou arquivadas. Reunião `IN_PROGRESS` nunca é apagada.
- **RN-03** O prazo é contado a partir do fim da reunião (`actual_end`); sem fim registrado, a partir da criação.

## Fora do escopo
- Busca semântica ("perguntas parecidas com esta").
- Estatísticas de desempenho nas entrevistas.
- Exportação em massa de todo o histórico.

## Critérios de aceite
- **CA-01**
  - "kafka" encontra a pergunta, a resposta e o trecho que citam Kafka, em reuniões de datas diferentes, inclusive arquivadas;
  - "Kafka" e "kafká" dão o mesmo resultado.
- **CA-02** A busca de um usuário da organização B não retorna nada da organização A.
- **CA-03** O link do resultado abre a sala na reunião certa, com o item destacado.
- **CA-04** Com `retention_days = 30`:
  - uma reunião concluída há 31 dias é apagada pela tarefa diária;
  - uma de 29 dias e uma `IN_PROGRESS` antiga não são;
  - o uso em `llm_usage` continua.
- **CA-05**
  - O selo aparece 7 dias antes;
  - "Manter por mais tempo" adia o prazo daquela reunião;
  - `MEMBER` não altera o prazo.
- **CA-06** A exportação inclui a transcrição completa e as perguntas e respostas.
