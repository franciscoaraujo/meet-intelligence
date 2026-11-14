-- Base de Conhecimento: documentos enviados pelo usuário (currículo, materiais de referência)
-- cujo texto extraído é usado como contexto pela IA nas respostas.
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(150),
    document_type VARCHAR(30) NOT NULL DEFAULT 'REFERENCE', -- RESUME, REFERENCE
    extracted_text TEXT NOT NULL,
    char_count INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_org ON knowledge_documents(organization_id);
