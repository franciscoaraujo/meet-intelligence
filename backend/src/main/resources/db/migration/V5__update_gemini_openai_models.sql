-- Modelos Gemini 1.x/2.x e OpenAI GPT-4.x foram desativados ou descontinuados.
-- Atualiza o padrão das colunas e as configurações existentes para os substitutos oficiais
-- (catálogo em com.meetingintelligence.domain.ai.AiModels).
ALTER TABLE organization_ai_configs ALTER COLUMN gemini_model SET DEFAULT 'gemini-3.5-flash-lite';
ALTER TABLE organization_ai_configs ALTER COLUMN openai_model SET DEFAULT 'gpt-6-luna';

-- gemini-1.5-*, gemini-2.0-* (desativados) e gemini-2.5-flash (substituto oficial: gemini-3.5-flash-lite)
UPDATE organization_ai_configs
SET gemini_model = 'gemini-3.5-flash-lite', updated_at = NOW()
WHERE gemini_model IS NULL
   OR gemini_model LIKE 'gemini-1.%'
   OR gemini_model LIKE 'gemini-2.0%'
   OR gemini_model LIKE 'gemini-2.5-flash%';

-- gemini-2.5-pro: substituto oficial gemini-3.8-flash
UPDATE organization_ai_configs
SET gemini_model = 'gemini-3.8-flash', updated_at = NOW()
WHERE gemini_model LIKE 'gemini-2.5-pro%';

-- gpt-4o / gpt-4o-mini (substituto oficial: gpt-6-luna) e gpt-4-turbo (substituto: família Sol)
UPDATE organization_ai_configs
SET openai_model = CASE
        WHEN openai_model LIKE 'gpt-4-turbo%' THEN 'gpt-6.1-sol'
        ELSE 'gpt-6-luna'
    END,
    updated_at = NOW()
WHERE openai_model IS NULL OR openai_model LIKE 'gpt-4%' OR openai_model LIKE 'gpt-3.5%';
