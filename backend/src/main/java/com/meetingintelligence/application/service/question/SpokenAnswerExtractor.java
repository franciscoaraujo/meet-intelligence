package com.meetingintelligence.application.service.question;

/**
 * Extrai, de um JSON que chega em pedaços, o valor do campo de primeiro nível {@code "spoken_answer"}
 * já decodificado (spec 008, RF-02). Cada chamada a {@link #feed} devolve só o texto novo do campo.
 * Chaves, estrutura e outros campos do JSON nunca saem daqui. Cercas markdown antes do JSON são
 * ignoradas, porque ficam fora de qualquer string.
 *
 * Não valida o JSON: o parse completo continua sendo feito no fim da resposta.
 */
public final class SpokenAnswerExtractor {

    static final String FIELD = "spoken_answer";

    private enum State { SCAN, EXPECT_VALUE, VALUE, DONE }

    private State state = State.SCAN;

    // Varredura fora do valor
    private int depth;
    private boolean inString;
    private boolean escape;
    private final StringBuilder currentString = new StringBuilder();
    private String pendingKey;

    // Decodificação do valor
    private boolean valueEscape;
    private StringBuilder unicode;
    private char heldHighSurrogate;

    private int emitted;

    public String feed(String chunk) {
        if (chunk == null || chunk.isEmpty() || state == State.DONE) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chunk.length() && state != State.DONE; i++) {
            char c = chunk.charAt(i);
            switch (state) {
                case SCAN -> scan(c);
                case EXPECT_VALUE -> {
                    if (c == '"') {
                        state = State.VALUE;
                    } else if (!Character.isWhitespace(c)) {
                        state = State.DONE; // valor não textual (null etc.)
                    }
                }
                case VALUE -> decode(c, out);
                default -> { }
            }
        }
        emitted += out.length();
        return out.toString();
    }

    /** O campo apareceu e o texto dele começou a sair. */
    public boolean started() {
        return emitted > 0;
    }

    /** A aspa de fechamento do campo já foi lida. */
    public boolean finished() {
        return state == State.DONE && emitted > 0;
    }

    private void scan(char c) {
        if (inString) {
            if (escape) {
                escape = false;
                appendKeyChar(c);
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                inString = false;
                pendingKey = depth == 1 ? currentString.toString() : null;
            } else {
                appendKeyChar(c);
            }
            return;
        }
        switch (c) {
            case '"' -> {
                inString = true;
                currentString.setLength(0);
                pendingKey = null;
            }
            case '{', '[' -> {
                depth++;
                pendingKey = null;
            }
            case '}', ']' -> {
                depth--;
                pendingKey = null;
            }
            case ':' -> {
                if (depth == 1 && FIELD.equals(pendingKey)) {
                    state = State.EXPECT_VALUE;
                }
                pendingKey = null;
            }
            default -> {
                if (!Character.isWhitespace(c)) pendingKey = null;
            }
        }
    }

    private void appendKeyChar(char c) {
        // Só chaves curtas interessam; strings longas (valores) não precisam ser guardadas inteiras
        if (currentString.length() <= FIELD.length()) currentString.append(c);
    }

    private void decode(char c, StringBuilder out) {
        if (unicode != null) {
            unicode.append(c);
            if (unicode.length() == 4) {
                char decoded;
                try {
                    decoded = (char) Integer.parseInt(unicode.toString(), 16);
                } catch (NumberFormatException e) {
                    decoded = '�';
                }
                unicode = null;
                emit(decoded, out);
            }
            return;
        }
        if (valueEscape) {
            valueEscape = false;
            switch (c) {
                case 'n' -> emit('\n', out);
                case 't' -> emit('\t', out);
                case 'r' -> emit('\r', out);
                case 'b' -> emit('\b', out);
                case 'f' -> emit('\f', out);
                case 'u' -> unicode = new StringBuilder(4);
                default -> emit(c, out); // \" \\ \/
            }
            return;
        }
        if (c == '\\') {
            valueEscape = true;
        } else if (c == '"') {
            if (heldHighSurrogate != 0) {
                out.append('�');
                heldHighSurrogate = 0;
            }
            state = State.DONE;
        } else {
            emit(c, out);
        }
    }

    /** Segura um high surrogate até o par chegar, para nunca enviar meio emoji à UI. */
    private void emit(char c, StringBuilder out) {
        if (heldHighSurrogate != 0) {
            if (Character.isLowSurrogate(c)) {
                out.append(heldHighSurrogate).append(c);
                heldHighSurrogate = 0;
                return;
            }
            out.append('�');
            heldHighSurrogate = 0;
        }
        if (Character.isHighSurrogate(c)) {
            heldHighSurrogate = c;
        } else {
            out.append(c);
        }
    }
}
