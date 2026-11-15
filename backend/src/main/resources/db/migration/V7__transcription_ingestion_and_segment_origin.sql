-- Spec 002: idempotência dos trechos de áudio e origem/horário de captura dos segmentos (aditiva).

-- Origem do áudio (TAB, MIC, UPLOAD, MANUAL) e horário no relógio da sessão de captura
ALTER TABLE transcript_segments ADD COLUMN IF NOT EXISTS audio_source VARCHAR(10);
ALTER TABLE transcript_segments ADD COLUMN IF NOT EXISTS capture_start_ms BIGINT;
ALTER TABLE transcript_segments ADD COLUMN IF NOT EXISTS capture_end_ms BIGINT;

-- Registro de cada trecho de áudio recebido, chaveado pelo id gerado no cliente.
-- Um reenvio do mesmo trecho (retry de rede) devolve o resultado já gravado, sem nova transcrição.
CREATE TABLE IF NOT EXISTS transcript_ingestions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    client_segment_id UUID NOT NULL,
    audio_source VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL,          -- PROCESSING, TRANSCRIBED, NO_SPEECH, FAILED
    provider VARCHAR(50),
    segment_id UUID REFERENCES transcript_segments(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ingestion_meeting_client_segment UNIQUE (meeting_id, client_segment_id)
);
