-- Spec 016: complemento ("e mais uma coisa...") juntado ao card da pergunta principal
ALTER TABLE interview_questions ADD COLUMN IF NOT EXISTS merged_into_id UUID REFERENCES interview_questions(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_interview_questions_merged_into ON interview_questions(merged_into_id) WHERE merged_into_id IS NOT NULL;
