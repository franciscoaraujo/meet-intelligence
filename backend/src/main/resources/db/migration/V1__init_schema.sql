-- ==============================================================================
-- MEETING INTELLIGENCE - V1 INITIAL DATABASE SCHEMA
-- ==============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

-- -----------------------------------------------------------------------------
-- 1. ORGANIZAÇÕES (Multi-Tenancy)
-- -----------------------------------------------------------------------------
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    plan_type VARCHAR(50) NOT NULL DEFAULT 'FREE', -- FREE, PRO, BUSINESS
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 2. USUÁRIOS
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) UNIQUE NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    avatar_url TEXT,
    google_id VARCHAR(255) UNIQUE,
    google_refresh_token TEXT, -- Criptografado com AES-256
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3. MEMBROS DA ORGANIZAÇÃO
-- -----------------------------------------------------------------------------
CREATE TABLE organization_members (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL DEFAULT 'MEMBER', -- OWNER, ADMIN, MEMBER
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(organization_id, user_id)
);

-- -----------------------------------------------------------------------------
-- 4. PERFIS DE IA PARA REUNIÕES
-- -----------------------------------------------------------------------------
CREATE TABLE ai_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    meeting_type VARCHAR(50) NOT NULL, -- INTERVIEW, TECHNICAL, SALES, CORPORATE, CLASS, GENERAL
    system_prompt_override TEXT,
    focus_categories JSONB,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 5. REUNIÕES
-- -----------------------------------------------------------------------------
CREATE TABLE meetings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    created_by_user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(500) NOT NULL,
    description TEXT,
    scheduled_start TIMESTAMP WITH TIME ZONE,
    scheduled_end TIMESTAMP WITH TIME ZONE,
    actual_start TIMESTAMP WITH TIME ZONE,
    actual_end TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED, IN_PROGRESS, PROCESSING, COMPLETED, FAILED
    google_meet_link VARCHAR(500),
    google_calendar_event_id VARCHAR(255),
    capture_mechanism VARCHAR(50) NOT NULL DEFAULT 'BROWSER_CAPTURE', -- GOOGLE_DRIVE, BOT, BROWSER_CAPTURE, FILE_UPLOAD
    ai_profile_id UUID REFERENCES ai_profiles(id),
    language VARCHAR(10) NOT NULL DEFAULT 'pt-BR',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_meetings_org_status ON meetings(organization_id, status);
CREATE INDEX idx_meetings_created_by ON meetings(created_by_user_id);

-- -----------------------------------------------------------------------------
-- 6. PARTICIPANTES
-- -----------------------------------------------------------------------------
CREATE TABLE meeting_participants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    speaker_tag INT, -- Identificador da diarização
    role VARCHAR(100),
    joined_at TIMESTAMP WITH TIME ZONE,
    left_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_participants_meeting ON meeting_participants(meeting_id);

-- -----------------------------------------------------------------------------
-- 7. GRAVAÇÕES E ARQUIVOS DE ÁUDIO
-- -----------------------------------------------------------------------------
CREATE TABLE recordings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    storage_path TEXT NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    duration_seconds INT,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED', -- UPLOADED, PROCESSING, READY, ERROR
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 8. SEGMENTOS BRUTOS DE TRANSCRIÇÃO
-- -----------------------------------------------------------------------------
CREATE TABLE transcript_segments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    participant_id UUID REFERENCES meeting_participants(id),
    speaker_label VARCHAR(100),
    original_text TEXT NOT NULL,
    corrected_text TEXT,
    start_time_ms BIGINT NOT NULL,
    end_time_ms BIGINT NOT NULL,
    confidence NUMERIC(4,3),
    language VARCHAR(10) NOT NULL DEFAULT 'pt-BR',
    source VARCHAR(50) NOT NULL DEFAULT 'DEEPGRAM',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_transcript_meeting_time ON transcript_segments(meeting_id, start_time_ms);

-- -----------------------------------------------------------------------------
-- 9. BLOCOS SEMÂNTICOS DE TRANSCRIÇÃO (CHUNKS)
-- -----------------------------------------------------------------------------
CREATE TABLE transcript_chunks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    text_content TEXT NOT NULL,
    start_time_ms BIGINT NOT NULL,
    end_time_ms BIGINT NOT NULL,
    processed_by_ai BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_chunks_meeting_index ON transcript_chunks(meeting_id, chunk_index);

-- -----------------------------------------------------------------------------
-- 10. EMBEDDINGS VETORIAIS (pgvector HNSW)
-- -----------------------------------------------------------------------------
CREATE TABLE meeting_embeddings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    chunk_id UUID REFERENCES transcript_chunks(id) ON DELETE CASCADE,
    embedding vector(1536) NOT NULL,
    text_preview TEXT NOT NULL,
    start_time_ms BIGINT,
    end_time_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_embeddings_hnsw ON meeting_embeddings USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_embeddings_meeting ON meeting_embeddings(meeting_id);

-- -----------------------------------------------------------------------------
-- 11. RESUMO DINÂMICO E CONTEXTO ACUMULADO
-- -----------------------------------------------------------------------------
CREATE TABLE meeting_summaries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID UNIQUE NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    version INT NOT NULL DEFAULT 1,
    executive_summary TEXT,
    key_topics JSONB,
    current_context TEXT,
    last_processed_chunk_index INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 12. INSIGHTS EXTRAÍDOS PELA IA
-- -----------------------------------------------------------------------------
CREATE TABLE ai_insights (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    category VARCHAR(50) NOT NULL, -- CONTEXT, QUESTION, DECISION, TASK, CONTENT_ANALYSIS, ALERT
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    start_time_ms BIGINT,
    end_time_ms BIGINT,
    speaker_reference VARCHAR(255),
    confidence NUMERIC(4,3),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_insights_meeting_category ON ai_insights(meeting_id, category);

-- -----------------------------------------------------------------------------
-- 13. TAREFAS / ACTION ITEMS IDENTIFICADOS
-- -----------------------------------------------------------------------------
CREATE TABLE meeting_tasks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    assignee_name VARCHAR(255),
    due_date_raw VARCHAR(255),
    due_date TIMESTAMP WITH TIME ZONE,
    priority VARCHAR(50) NOT NULL DEFAULT 'MEDIUM', -- LOW, MEDIUM, HIGH, URGENT
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING', -- PENDING, IN_PROGRESS, COMPLETED, CANCELLED
    confidence NUMERIC(4,3),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tasks_meeting_status ON meeting_tasks(meeting_id, status);

-- -----------------------------------------------------------------------------
-- 14. DECISÕES TOMADAS
-- -----------------------------------------------------------------------------
CREATE TABLE meeting_decisions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    justification TEXT,
    responsible_parties TEXT,
    consequences TEXT,
    timestamp_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_decisions_meeting ON meeting_decisions(meeting_id);

-- -----------------------------------------------------------------------------
-- 15. CHAT INTERATIVO RAG
-- -----------------------------------------------------------------------------
CREATE TABLE ai_conversations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(255) NOT NULL DEFAULT 'Chat da Reunião',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE ai_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    sender_type VARCHAR(20) NOT NULL, -- USER, ASSISTANT, SYSTEM
    content TEXT NOT NULL,
    citations JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_messages_conversation ON ai_messages(conversation_id);

-- -----------------------------------------------------------------------------
-- 16. CONSUMO E TELEMETRIA DE LLM
-- -----------------------------------------------------------------------------
CREATE TABLE llm_usage (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    meeting_id UUID REFERENCES meetings(id) ON DELETE SET NULL,
    provider VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    operation_type VARCHAR(100) NOT NULL,
    prompt_tokens INT NOT NULL,
    completion_tokens INT NOT NULL,
    total_tokens INT NOT NULL,
    estimated_cost_usd NUMERIC(10,6),
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_llm_usage_org_created ON llm_usage(organization_id, created_at);

-- -----------------------------------------------------------------------------
-- 17. OUTBOX TRANSACIONAL (Garantia At-Least-Once Delivery)
-- -----------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING', -- PENDING, PUBLISHED, FAILED
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_outbox_pending_events ON outbox_events(status, created_at);

