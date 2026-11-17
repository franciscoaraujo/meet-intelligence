-- Spec 007 (RF-14): as chaves de IA passam a ser gravadas criptografadas (AES-256-GCM, prefixo enc:v1:).
-- O valor cifrado é maior que o original; TEXT evita o limite de 500 caracteres.
-- A cifragem dos valores existentes é feita pela aplicação na inicialização (SecretEncryptionMigrator),
-- porque a chave de criptografia não pode ficar no SQL.
ALTER TABLE organization_ai_configs ALTER COLUMN gemini_api_key TYPE TEXT;
ALTER TABLE organization_ai_configs ALTER COLUMN openai_api_key TYPE TEXT;
ALTER TABLE organization_ai_configs ALTER COLUMN grok_api_key TYPE TEXT;
ALTER TABLE organization_ai_configs ALTER COLUMN claude_api_key TYPE TEXT;
