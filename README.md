# Meet Intelligence — Plataforma SaaS de Inteligência Contínua para Reuniões

O **Meet Intelligence** é uma plataforma SaaS corporativa projetada para capturar, gravar, transcrever, interpretar e analisar reuniões realizadas pelo **Google Meet** e outras fontes de áudio, combinando inteligência artificial automática multi-provedor (**BYOK: Google Gemini, xAI Grok e Anthropic Claude**) e assistente interativo em tempo real via **RAG com pgvector**.

---

## 🚀 Arquitetura e Tecnologias

- **Backend (`/backend`)**: Java 21 LTS, Spring Boot 3.3, Spring Security OAuth2 & JWT Multi-Tenant, Spring Data JPA, Spring WebSocket (STOMP), Flyway Migrations (`V1`, `V2`), Resilience4j Circuit Breaker, Apache POI (DOCX), iText 7 (PDF).
- **Frontend (`/frontend`)**: React 19, TypeScript, Vite, **Google Material Design 3 (`@material/web`)**, Tailwind CSS v4, Lucide Icons, Axios, STOMP.js / SockJS.
- **Banco de Dados & Vetores**: PostgreSQL 16 com extensão `pgvector` nativa (Índice HNSW para busca semântica por proximidade de cosseno `<=>`).
- **Mensageria**: RabbitMQ 3.13 com Transactional Outbox Pattern e Dead Letter Queues (DLQ).
- **Object Storage**: S3-Compatible (Silo / MinIO / AWS S3) para armazenamento seguro de áudio e mídias via Presigned URLs.
- **Cache & Rate Limit**: Redis 7.

---

## 🛠️ Estrutura de Portas do Ambiente Local

| Serviço | Porta Externa | Porta Interna | Descrição |
| :--- | :--- | :--- | :--- |
| **Backend API** | `8085` | `8085` | API REST e WebSockets do Spring Boot |
| **Frontend SPA** | `5173` | `5173` | Interface Web Material Design 3 (Vite + React) |
| **PostgreSQL + pgvector** | `5435` | `5432` | Banco relacional e vetorial |
| **Redis** | `6389` | `6379` | Cache e rate limiting |
| **RabbitMQ AMQP** | `5675` | `5672` | Broker de mensageria assíncrona |
| **RabbitMQ Management** | `15675` | `15672` | Painel web do RabbitMQ |
| **S3 Storage API** | `9000` | `9000` | Armazenamento de áudio e mídia |
| **S3 Console** | `9001` | `9001` | Painel web de gerenciamento S3 |

---

## ⚡ Como Executar Localmente

Inicie toda a infraestrutura com o script PowerShell na raiz do projeto:

```powershell
.\start-dev.ps1
```

Ou execute separadamente:

### 1. Infraestrutura Docker Compose
```powershell
docker-compose up -d
```

### 2. Backend (`/backend`)
```powershell
.\run-backend.ps1
```
- **API Base**: `http://localhost:8085`
- **Swagger / OpenAPI**: `http://localhost:8085/swagger-ui.html`

### 3. Frontend (`/frontend`)
```powershell
.\run-frontend.ps1
```
- **Interface Web**: `http://localhost:5173`

---

## 🌟 Principais Diferenciais e Funcionalidades

### 1. Motor Multi-IA Corporativo com BYOK (Bring Your Own Key)
- Cada organização pode escolher e configurar sua própria chave de API e modelo preferido em **Configurações**:
  - **Google Gemini** (`gemini-3.5-flash-lite` padrão, `gemini-4-hydrogen`, `gemini-3.8-flash`)
  - **xAI Grok** (`grok-4.7` padrão, `grok-4.3`, `grok-4.20-0309-non-reasoning`) via Responses API
  - **Anthropic Claude** (`claude-opus-5-5` padrão, `claude-sonnet-5-5`, `claude-haiku-5-5`)
- Transcrição: Deepgram `nova-3` e OpenAI `gpt-transcribe`; embeddings: OpenAI `text-embedding-3-small`.
- Todos os identificadores ficam centralizados em `backend/.../domain/ai/AiModels.java` (revisado em 2026-11-15).
- Teste de conectividade e latência em tempo real diretamente pela interface, com fallback automático via Circuit Breaker.

### 2. Sincronização Estrita com Google Calendar & Google Meet
- Integração direta com a API oficial do Google Calendar v3 (`https://www.googleapis.com/calendar/v3/calendars/primary/events`).
- Filtra e importa **exclusivamente reuniões reais agendadas** que possuam conferência do **Google Meet** ativa (`hangoutLink` ou `conferenceData`), sem jamais criar reuniões fictícias e evitando duplicações por `googleCalendarEventId`.

### 3. Interface Profissional em Google Material Design 3 (`@material/web`)
- Estética corporativa alinhada ao **Google Workspace / Google Meet**:
  - Paleta clara de alto contraste (`#f8fafd`, `#ffffff`, `#0b57d0`, `#d3e3fd`).
  - Sala de Reunião Ao Vivo dividida em 3 painéis sincronizados via WebSocket STOMP: **Transcrição Auditável**, **Insights Automáticos (Decisões, Tarefas, Resumo)** e **Chat RAG com Citações Verificáveis**.

### Transcrição: cadeia de provedores e flags
A transcrição ao vivo tenta, nesta ordem, só os provedores com chave: Gemini (organização) → gpt-transcribe (chave OpenAI legada da organização) → Deepgram (`DEEPGRAM_API_KEY`) → gpt-transcribe (`OPENAI_API_KEY`). Cada trecho termina como `TRANSCRIBED`, `NO_SPEECH` ou `FAILED` (nunca texto inventado). Detalhes: `specs/002-transcription-fallback/`.

| Variável | Padrão | Uso |
|---|---|---|
| `LOCAL_GOOGLE_STT_ENABLED` | `false` | STT via Python/speech_recognition (endpoint não oficial do Google). Só desenvolvimento. |
| `SPEECH_SIMULATION_ENABLED` | `false` | Transcrição simulada (`source=SIMULATION`). Só desenvolvimento. |

Testes: `mvn test` no backend (os testes de integração usam Docker/Testcontainers e são pulados sem Docker) e `npm test` no frontend.

### 4. Exportação Formal de Atas e Governança LGPD
- Exportação consolidada de atas executivas em **PDF**, **DOCX (Word)** e **TXT**.
- Conformidade com o **Art. 18 da LGPD (Direito ao Esquecimento)**: exclusão em cascata de mídias no S3, transcrições e embeddings vetoriais no `pgvector`.
