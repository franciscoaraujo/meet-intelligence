package com.meetingintelligence.domain.speech;

/**
 * Pedido de transcrição para um provedor.
 *
 * @param audio    bytes do áudio
 * @param mimeType tipo do áudio (ex.: audio/wav)
 * @param language idioma BCP-47 (ex.: pt-BR)
 * @param mode     LIVE (trechos curtos, timeouts curtos) ou BATCH (arquivos enviados)
 */
public record SttRequest(byte[] audio, String mimeType, String language, Mode mode) {

    public enum Mode { LIVE, BATCH }

    public String languageOrDefault() {
        return (language == null || language.isBlank() || language.equalsIgnoreCase("auto")) ? "pt-BR" : language;
    }

    public String isoLanguage() {
        return languageOrDefault().split("-")[0];
    }

    public boolean isWav() {
        return (mimeType != null && mimeType.toLowerCase().contains("wav"))
                || (audio != null && audio.length > 12 && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F');
    }
}
