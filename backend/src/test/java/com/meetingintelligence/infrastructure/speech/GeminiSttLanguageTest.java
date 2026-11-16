package com.meetingintelligence.infrastructure.speech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 011, CA-02: o prompt de transcrição do Gemini segue o idioma da reunião. */
class GeminiSttLanguageTest {

    @Test
    @DisplayName("fr-FR pede francês, en-US pede inglês, pt-BR (ou vazio) pede português; nunca pede tradução")
    void promptFollowsLanguage() {
        assertThat(GeminiAudioSttProvider.prompt("fr-FR")).contains("em francês (fr-FR)").contains("Não traduza");
        assertThat(GeminiAudioSttProvider.prompt("en-US")).contains("em inglês (en-US)");
        assertThat(GeminiAudioSttProvider.prompt("pt-BR")).contains("em português do Brasil (pt-BR)");
        assertThat(GeminiAudioSttProvider.prompt(null)).contains("português do Brasil");
        assertThat(GeminiAudioSttProvider.prompt("fr-FR")).contains(GeminiAudioSttProvider.NO_SPEECH_MARKER);
    }
}
