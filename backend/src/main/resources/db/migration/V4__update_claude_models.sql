-- Os modelos Claude 3.x configurados por padrão foram descontinuados pela Anthropic.
-- Atualiza o padrão da coluna e as configurações existentes para a geração atual.
ALTER TABLE organization_ai_configs ALTER COLUMN claude_model SET DEFAULT 'claude-opus-5-5';

UPDATE organization_ai_configs
SET claude_model = CASE
        WHEN claude_model LIKE 'claude-3-haiku%' OR claude_model LIKE 'claude-3-5-haiku%' THEN 'claude-haiku-5-5'
        ELSE 'claude-opus-5-5'
    END,
    updated_at = NOW()
WHERE claude_model IS NULL OR claude_model LIKE 'claude-3%';
