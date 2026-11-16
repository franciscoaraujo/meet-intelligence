-- Spec 014: tom das respostas do copiloto por reunião (NEUTRAL, INFORMAL, FORMAL, TECHNICAL).
-- Reuniões existentes ficam com o tom padrão.
ALTER TABLE meetings ADD COLUMN IF NOT EXISTS answer_style VARCHAR(20) NOT NULL DEFAULT 'NEUTRAL';
