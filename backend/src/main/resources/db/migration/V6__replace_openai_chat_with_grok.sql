-- O provedor de chat OpenAI GPT foi substituído pelo xAI Grok.
-- As colunas openai_* são mantidas: a chave OpenAI já salva ainda serve de fallback
-- na transcrição do áudio da aba do Meet.
ALTER TABLE organization_ai_configs ADD COLUMN IF NOT EXISTS grok_api_key VARCHAR(500);
ALTER TABLE organization_ai_configs ADD COLUMN IF NOT EXISTS grok_model VARCHAR(100) DEFAULT 'grok-4.7';

UPDATE organization_ai_configs SET grok_model = 'grok-4.7' WHERE grok_model IS NULL;

-- Organizações que usavam GPT como provedor ativo passam para o Gemini (o Grok ainda não tem chave salva)
UPDATE organization_ai_configs SET active_provider = 'GEMINI', updated_at = NOW() WHERE active_provider = 'OPENAI';
