package com.meetingintelligence.domain.language;

import java.util.Arrays;

/**
 * Idiomas suportados na entrevista (spec 011). O código BCP-47 é o valor gravado em
 * {@code meeting.language} e enviado aos provedores de transcrição.
 */
public enum InterviewLanguage {
    PT_BR("pt-BR", "português do Brasil", "Português"),
    EN_US("en-US", "inglês", "English"),
    FR_FR("fr-FR", "francês", "Français");

    private final String tag;
    private final String namePt;
    private final String nativeName;

    InterviewLanguage(String tag, String namePt, String nativeName) {
        this.tag = tag;
        this.namePt = namePt;
        this.nativeName = nativeName;
    }

    public String tag() { return tag; }
    /** Nome em português, usado nas instruções dos prompts ("inglês"). */
    public String namePt() { return namePt; }
    public String nativeName() { return nativeName; }

    /** Tolerante: nulo, vazio ou desconhecido → português; aceita "en", "en-US", "EN_US". */
    public static InterviewLanguage fromTag(String value) {
        if (value == null || value.isBlank()) return PT_BR;
        String v = value.trim().replace('_', '-').toLowerCase();
        for (InterviewLanguage l : values()) {
            String t = l.tag.toLowerCase();
            if (v.equals(t) || v.equals(t.substring(0, 2))) return l;
        }
        return PT_BR;
    }

    /** Estrito (para a API): só os códigos suportados; outro valor → IllegalArgumentException (400). */
    public static InterviewLanguage parseStrict(String value) {
        return Arrays.stream(values())
                .filter(l -> l.tag.equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Idioma não suportado: " + value + ". Use pt-BR, en-US ou fr-FR."));
    }
}
