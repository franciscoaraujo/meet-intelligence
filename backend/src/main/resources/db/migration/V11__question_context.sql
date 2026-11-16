-- Spec 015: fala do entrevistador logo antes da pergunta (cenário a que ela se refere)
ALTER TABLE interview_questions ADD COLUMN IF NOT EXISTS context_text TEXT;
