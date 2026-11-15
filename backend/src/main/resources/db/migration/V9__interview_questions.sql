-- Spec 005: perguntas detectadas, vínculo dos insights com a origem, eco provável e unicidade dos blocos.

CREATE TABLE IF NOT EXISTS interview_questions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,              -- DETECTED, ANSWERING, ANSWERED, FAILED, DISMISSED
    question_text TEXT NOT NULL,
    utterance_type VARCHAR(30) NOT NULL,
    score NUMERIC(4,3) NOT NULL,
    auto_answer BOOLEAN NOT NULL DEFAULT FALSE,
    source_segment_ids UUID[] NOT NULL DEFAULT '{}',
    capture_start_ms BIGINT,
    capture_end_ms BIGINT,
    answer_insight_id UUID,
    answer_version INT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_interview_questions_meeting ON interview_questions(meeting_id, created_at);

-- Insight rastreável até a origem (P-13)
ALTER TABLE ai_insights ADD COLUMN IF NOT EXISTS question_id UUID REFERENCES interview_questions(id) ON DELETE SET NULL;
ALTER TABLE ai_insights ADD COLUMN IF NOT EXISTS chunk_id UUID REFERENCES transcript_chunks(id) ON DELETE SET NULL;
ALTER TABLE ai_insights ADD COLUMN IF NOT EXISTS source_segment_ids UUID[];

-- Fala do microfone que repete a da aba (alto-falante captado pelo microfone)
ALTER TABLE transcript_segments ADD COLUMN IF NOT EXISTS echo_suspected BOOLEAN NOT NULL DEFAULT FALSE;

-- chunk_index era calculado como count+1 sem trava (P-11): renumera eventuais duplicados e passa a ser único
WITH ordered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY meeting_id ORDER BY chunk_index, created_at) AS rn
    FROM transcript_chunks
)
UPDATE transcript_chunks c SET chunk_index = o.rn FROM ordered o WHERE c.id = o.id AND c.chunk_index <> o.rn;
DROP INDEX IF EXISTS idx_chunks_meeting_index;
CREATE UNIQUE INDEX IF NOT EXISTS uq_chunks_meeting_index ON transcript_chunks(meeting_id, chunk_index);
