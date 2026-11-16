package com.meetingintelligence.domain.speech;

/**
 * Porta de um provedor de speech-to-text.
 *
 * Contrato: nunca lança exceção por falha do provedor (devolve {@link TranscriptionOutcome.Failed})
 * e nunca devolve texto que não veio do áudio.
 */
public interface AudioTranscriber {

    /** Nome gravado em transcript_segments.source (ex.: GEMINI, DEEPGRAM). */
    String providerName();

    /**
     * @param credentials chave (e modelo, quando aplicável) a usar nesta chamada: da organização ou do
     *                    servidor. Provedores sem chave (ex.: STT local) ignoram o parâmetro.
     */
    TranscriptionOutcome transcribe(SttRequest request, SttCredentials credentials);

    /** Chave de API e modelo opcional para uma chamada de STT. */
    record SttCredentials(String apiKey, String model) {
        public static SttCredentials none() {
            return new SttCredentials(null, null);
        }

        @Override
        public String toString() {
            // Nunca imprime a chave
            return "SttCredentials[model=" + model + "]";
        }
    }
}
