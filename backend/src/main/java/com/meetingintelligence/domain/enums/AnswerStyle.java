package com.meetingintelligence.domain.enums;

import java.util.Arrays;

/**
 * Tom das respostas do copiloto (spec 014). Muda o tom, nunca os fatos: a fidelidade ao currículo e
 * o tamanho da resposta falada continuam iguais.
 */
public enum AnswerStyle {
    /** Padrão: tom profissional neutro (comportamento anterior à spec 014). */
    NEUTRAL(null),
    INFORMAL("Tom informal de conversa: frases curtas e diretas, como quem explica para um colega; menos jargão, "
            + "sem gírias pesadas e sem perder a precisão técnica."),
    FORMAL("Tom formal: vocabulário cuidado, sem gírias nem expressões coloquiais; organize a fala em contexto, "
            + "solução e resultado."),
    TECHNICAL("Tom técnico: use termos precisos, nomes de padrões e de tecnologias, trade-offs explícitos e números "
            + "do currículo quando existirem. Não infle perguntas simples: a profundidade continua proporcional à pergunta.");

    private final String instruction;

    AnswerStyle(String instruction) {
        this.instruction = instruction;
    }

    /** Instrução para o prompt; nula no estilo padrão. */
    public String instruction() {
        return instruction;
    }

    /** Estrito (para a API): outro valor → IllegalArgumentException (400). */
    public static AnswerStyle parseStrict(String value) {
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Estilo não suportado: " + value + ". Use NEUTRAL, INFORMAL, FORMAL ou TECHNICAL."));
    }
}
