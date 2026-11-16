package com.meetingintelligence.domain.speech;

import java.util.List;

/**
 * Resultado de uma tentativa de transcrição. Distingue explicitamente:
 * <ul>
 *   <li>{@link Transcribed}: o provedor reconheceu fala;</li>
 *   <li>{@link NoSpeech}: o provedor processou o áudio e não há fala (sucesso sem conteúdo);</li>
 *   <li>{@link Failed}: o provedor não conseguiu processar (aciona o próximo da cadeia).</li>
 * </ul>
 * Uma lista vazia nunca representa sucesso (causa do problema P-01).
 */
public sealed interface TranscriptionOutcome {

    String provider();

    record Transcribed(String provider, List<TranscriptionSegmentDto> segments) implements TranscriptionOutcome {
        public Transcribed {
            if (segments == null || segments.isEmpty()) {
                throw new IllegalArgumentException("Transcribed exige ao menos um segmento; use NoSpeech para ausência de fala");
            }
            segments = List.copyOf(segments);
        }
    }

    record NoSpeech(String provider) implements TranscriptionOutcome {}

    /**
     * @param detail descrição técnica curta (código HTTP, tipo de exceção). Nunca contém áudio, texto
     *               transcrito ou chave de API.
     */
    record Failed(String provider, FailureReason reason, String detail) implements TranscriptionOutcome {
        public boolean retryable() {
            return reason.retryable();
        }
    }
}
