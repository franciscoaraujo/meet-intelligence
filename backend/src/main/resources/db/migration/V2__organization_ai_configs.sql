-- Criação da tabela de configuração BYOK (Bring Your Own Key) para Inteligência Artificial por Organização
CREATE TABLE IF NOT EXISTS organization_ai_configs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    active_provider VARCHAR(50) NOT NULL DEFAULT 'GEMINI', -- GEMINI, OPENAI, CLAUDE
    gemini_api_key VARCHAR(500),
    gemini_model VARCHAR(100) DEFAULT 'gemini-1.5-flash',
    openai_api_key VARCHAR(500),
    openai_model VARCHAR(100) DEFAULT 'gpt-4o-mini',
    claude_api_key VARCHAR(500),
    claude_model VARCHAR(100) DEFAULT 'claude-3-5-sonnet-20241022',
    temperature DOUBLE PRECISION DEFAULT 0.20,
    use_organization_keys BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_org_ai_config_org_id ON organization_ai_configs(organization_id);
