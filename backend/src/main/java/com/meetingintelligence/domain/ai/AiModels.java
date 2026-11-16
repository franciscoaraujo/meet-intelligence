package com.meetingintelligence.domain.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Catálogo único dos modelos de IA usados pela aplicação (padrões e preços).
 *
 * Todo identificador de modelo deve vir daqui, para que a troca de geração seja feita
 * num só lugar. Revisado em 2026-11-15 contra as páginas oficiais de modelos e
 * descontinuação da Anthropic, Google, xAI, OpenAI e Deepgram.
 */
public final class AiModels {

    private AiModels() {}

    // ---------------------------------------------------------------- LLMs (BYOK)

    /** Anthropic: modelo padrão recomendado. Alternativas: claude-sonnet-5-5, claude-haiku-5-5. */
    public static final String CLAUDE_DEFAULT = "claude-opus-5-5";

    /**
     * Google: rápido e barato, aceita áudio (usado também na transcrição da aba do Meet).
     * Substituto oficial do gemini-2.5-flash. Alternativas: gemini-4-hydrogen, gemini-3.8-flash.
     */
    public static final String GEMINI_DEFAULT = "gemini-3.5-flash-lite";

    /**
     * xAI: modelo mais capaz, via Responses API. Alternativas: grok-4.3 (menor custo),
     * grok-4.20-0309-non-reasoning (sem raciocínio, menor latência).
     */
    public static final String GROK_DEFAULT = "grok-4.7";

    // ------------------------------------------------------- Transcrição e vetores

    /** OpenAI speech-to-text (whisper-1 e gpt-4o-transcribe foram descontinuados). */
    public static final String OPENAI_TRANSCRIBE = "gpt-transcribe";

    /** Deepgram pré-gravado: recomendado para reuniões com vários falantes; suporta pt-BR. */
    public static final String DEEPGRAM_STT = "nova-3";

    /**
     * Preço do Deepgram Nova-3 pré-gravado, plano pago por uso: US$ 0,0043/min (spec 018; fontes de
     * terceiros consultadas em 2026-11-16, confirmar em deepgram.com/pricing).
     */
    public static final double DEEPGRAM_STT_USD_PER_HOUR = 0.258;

    /** xAI speech-to-text (spec 017): a mesma chave do Grok das respostas. Alternativa: grok-voice-transcribe-1.0. */
    public static final String GROK_STT = "grok-voice-transcribe-2.0";

    /** Preço publicado do STT REST da xAI, em US$ por hora de áudio (consultado em 2026-11-16). */
    public static final double GROK_STT_USD_PER_HOUR = 0.10;

    /**
     * Embeddings: continua ativo. Não trocar sem migrar a coluna vector(1536) e
     * reindexar os embeddings já gravados.
     */
    public static final String OPENAI_EMBEDDING = "text-embedding-3-small";

    // ------------------------------------------------------------------ Custos

    /** Custo estimado em USD a partir do preço por milhão de tokens (entrada/saída) de cada modelo. */
    public static BigDecimal estimateCost(String model, int inputTokens, int outputTokens) {
        double[] price = pricePerMillion(model);
        return BigDecimal.valueOf((inputTokens * price[0] + outputTokens * price[1]) / 1_000_000d)
                .setScale(6, RoundingMode.HALF_UP);
    }

    static double[] pricePerMillion(String model) {
        String m = model == null ? "" : model;
        // Anthropic
        if (m.startsWith("claude-opus-5-5")) return new double[]{4.0, 20.0};
        if (m.startsWith("claude-sonnet-5")) return new double[]{2.0, 10.0};
        if (m.startsWith("claude-haiku-5-5")) return new double[]{0.10, 0.50};
        if (m.startsWith("claude-fable-5-2")) return new double[]{8.0, 40.0};
        if (m.startsWith("claude-opus")) return new double[]{5.0, 25.0};
        // Google
        if (m.startsWith("gemini-4-hydrogen")) return new double[]{1.50, 7.50};
        if (m.startsWith("gemini-3.8-flash")) return new double[]{1.50, 7.50};
        if (m.startsWith("gemini-3.5-flash-lite")) return new double[]{0.30, 2.50};
        // xAI (contextos de até 200k tokens)
        if (m.startsWith("grok-4.7") || m.startsWith("grok-4.6") || m.startsWith("grok-4.5")) return new double[]{2.0, 6.0};
        if (m.startsWith("grok-4.3") || m.startsWith("grok-4.20")) return new double[]{1.25, 2.50};
        return new double[]{3.0, 15.0};
    }

}
