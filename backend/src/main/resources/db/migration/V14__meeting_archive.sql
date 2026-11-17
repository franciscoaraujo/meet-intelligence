-- Spec 020 (RF-08): arquivar esconde a reunião da lista sem apagar nenhum dado.
ALTER TABLE meetings ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_meetings_org_archived ON meetings (organization_id, archived_at);
