# Ideias futuras

Ideias discutidas e guardadas para depois. Nenhuma tem implementação prevista. Cada uma vira spec só quando for priorizada, seguindo o processo do [README](README.md).

Última atualização: 2026-11-16.

## Produto

### 1. Pacote "Entrevista": perfil de comunicação, histórias STAR e simulador
**Origem:** conversa de 2026-11-16 ("teste de personalidade para a IA responder de acordo com a personalidade do cliente").

**Ideia:** um pacote pago para quem está em processo seletivo, com respostas que soam como o próprio candidato.

**O pacote:**
- Questionário guiado que gera um **perfil de comunicação** (direto ou detalhista, formal ou descontraído). Base reconhecida (Big Five) ou sem rótulo, em vez de MBTI ou DISC.
- **Banco de histórias STAR** (situação, tarefa, ação, resultado): 6 a 10 histórias reais para as perguntas comportamentais ("conflito", "maior erro", "por que sair").
- Perfil e histórias entram na Base de Conhecimento. O copiloto usa os dois, com a mesma regra de fidelidade (só fatos informados).
- **Simulador de entrevista:** a IA pergunta, o candidato responde e recebe feedback no próprio estilo (técnica e comportamental, PT/EN/FR).
- Cobrança por período (ex.: 30 dias de preparação).

**Avaliação:**
- Vendável como pacote de **preparação**. O teste de personalidade sozinho é fraco.
- Diferenciais: mercado brasileiro e validação contra experiência inventada.

**Riscos:**
- O copiloto ao vivo em entrevista é visto como "cola" por muitas empresas: candidato eliminado e crítica pública.
- Prometer "se preparar e responder com segurança", não "passar em entrevistas".
- Dados pessoais sensíveis: LGPD; o modo local vira argumento de venda.

### 2. Tamanho da resposta
Ajuste separado do tom (spec 014): "curta" ou "completa". Hoje a resposta falada é fixa entre 60 e 140 palavras.

### 3. Leitura da tela
Usar a imagem da tela capturada, que hoje é descartada, para ler perguntas mostradas na reunião (exercício de código, diagrama) e mandá-las ao copiloto. Spec separada da 009.

## IA e custo

### 4. Provedor local (Ollama) com roteamento por tarefa
**Origem:** conversa de 2026-11-16.

**Ideia:**
- Provedor `OLLAMA` opcional.
- Nas Configurações, cada tarefa escolhe o provedor. Exemplo: copiloto no Grok; ata, insights, "Consolidar", chat RAG e tradução no Ollama.
- O fallback do orquestrador continua valendo.

**Para quem:**
- Reuniões sensíveis (saúde, jurídico, RH): o conteúdo não sai do computador.
- Reuniões corporativas, aulas e vendas.
- Alto volume sem custo por token.

**Limites:**
- Na máquina atual (Ryzen 7 5700G, sem placa de vídeo dedicada, 31 GB de RAM), a estimativa é de 15 a 40 s até a primeira palavra. Não serve para o copiloto ao vivo.
- Embeddings locais têm outra dimensão de vetor: exigem migration e reindexação da Base de Conhecimento.

**Primeiro passo:** instalar o Ollama e medir ata e insights com uma reunião real antes de escrever a spec.

### 5. Gemini na camada gratuita
Uma chave do Google AI Studio cobre a transcrição (hoje no STT local provisório) e o copiloto, sem custo dentro dos limites.

Atenção: na camada gratuita, o Google pode usar os dados para melhorar os modelos.

### 6. STT local com Faster-Whisper
Transcrição local de verdade, como **opção**. O STT local atual usa o serviço web gratuito do Google e é provisório.

### 7. Escolha de modelo por custo e latência
Ajuste automático do modelo conforme a tarefa e o orçamento.

## Captura e transcrição

### 8. Diarização de vários participantes no áudio do computador
Hoje todo o áudio do computador é um único "Participante remoto".

### 9. Streaming de áudio
Enviar o áudio continuamente para reduzir a latência da transcrição. O streaming de tokens do copiloto já foi feito na spec 008.

## Interface

### 10. Mensagens de erro em Configurações
Trocar os 3 `alert()` restantes (plano, convite, configuração de IA) por mensagens no próprio app, como o `ConfirmDialog` (spec 013).

### 11. Interface limpa nas outras telas
Levar o tratamento da spec 013 aos painéis de Insights e Chat, ao Dashboard e à lista de reuniões.

### 12. Interface em outros idiomas
A spec 011 mudou o idioma da entrevista; a interface, os insights e a ata continuam em português.

## Infraestrutura

### 13. Processamento distribuído via RabbitMQ
Necessário quando houver mais de uma instância do backend. Hoje a montagem de enunciados e a execução serial ficam em memória.

## Já planejado (fora desta lista)
- **Spec 007 (P1):** segurança. WebSocket autenticado, isolamento entre organizações, chaves de IA criptografadas.
