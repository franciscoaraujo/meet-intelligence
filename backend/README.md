# Meet Intelligence — Backend API (Spring Boot 3.3 + Java 21)

Módulo de Backend da plataforma **Meet Intelligence**, construído com **Java 21 LTS** e **Spring Boot 3.3**, responsável pela autenticação multi-tenant, ingestão de áudio, transcrição em tempo real (STT), orquestração de múltiplos LLMs (**Google Gemini, OpenAI GPT e Anthropic Claude**), busca vetorial com **pgvector** e sincronização estrita com o **Google Meet**.

---

## 📂 Estrutura de Pacotes (`com.meetintelligence`)

- **`config/`**: Configurações de segurança JWT/OAuth2, CORS, WebSocket STOMP, S3/MinIO, RabbitMQ e OpenAPI.
- **`controller/`**:
  - `AuthController`: Login corporativo e Google OAuth 2.0.
  - `CalendarController`: Conexão OAuth e sincronização estrita de reuniões agendadas no Google Meet (`/api/v1/calendar/sync`).
  - `MeetingController`: Ciclo de vida de reuniões, upload de mídia, transcrições, insights e exclusão LGPD.
  - `RagChatController`: Chat contextual sobre a reunião com busca semântica no `pgvector`.
  - `MeetingExportController`: Exportação de atas formais em PDF, DOCX e TXT.
  - `OrganizationAiConfigController`: Configuração Multi-IA BYOK por organização (`GET/PUT /api/v1/organization/ai-config` e teste de conexão `POST /api/v1/organization/ai-config/test`).
  - `OrganizationController` & `DashboardController`: Gestão de planos SaaS (`FREE`, `PRO`, `BUSINESS`), membros RBAC e telemetria de uso/custos de IA.
- **`service/ai/`**:
  - `AIOrchestrator`: Roteador dinâmico entre provedores de IA com suporte a chave própria do cliente (BYOK) e Circuit Breaker Resilience4j.
  - `GeminiLlmProvider`: Integração REST nativa com Google Gemini API.
  - `GrokLlmProvider`: Integração REST com a xAI Responses API (`POST /v1/responses`).
  - `OpenAiEmbeddingProvider`: Embeddings da OpenAI para a busca semântica (pgvector).
  - `ClaudeLlmProvider`: Integração REST nativa com Anthropic Messages API (`v1/messages`).
- **`domain/` & `repository/`**: Entidades JPA multi-tenant e repositórios com consultas vetoriais nativas (`<=>` distância de cosseno no PostgreSQL `pgvector`).

---

## 🗄️ Migrações Flyway (`src/main/resources/db/migration`)

- **`V1__init_schema.sql`**: Criação da extensão `vector`, tabelas multi-tenant (`organizations`, `users`, `meetings`, `recordings`, `transcript_segments`, `ai_insights`, `meeting_embeddings` com índice HNSW, etc.).
- **`V2__organization_ai_configs.sql`**: Tabela `organization_ai_configs` para armazenamento isolado das credenciais BYOK por tenant (hoje Gemini, Grok e Claude; ver `V6__replace_openai_chat_with_grok.sql`).

---

## ▶️ Execução e Testes

```powershell
# Compilar e rodar os 9 testes automatizados
mvn clean test

# Iniciar o servidor na porta 8085
mvn spring-boot:run
```

