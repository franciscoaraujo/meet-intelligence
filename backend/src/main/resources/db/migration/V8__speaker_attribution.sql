-- Spec 003: papel e confiança do falante por segmento; papel do usuário na entrevista (aditiva).
ALTER TABLE transcript_segments ADD COLUMN IF NOT EXISTS speaker_role VARCHAR(10);
ALTER TABLE transcript_segments ADD COLUMN IF NOT EXISTS speaker_confidence NUMERIC(4,3);

ALTER TABLE meetings ADD COLUMN IF NOT EXISTS user_interview_role VARCHAR(20);
