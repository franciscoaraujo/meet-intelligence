package com.meetingintelligence.domain.security;

/**
 * Protege segredos gravados no banco (spec 007, RF-14). A implementação (AES-256-GCM) fica na
 * infraestrutura; o domínio só declara a necessidade.
 */
public interface SecretProtector {

    /** Texto puro → valor a gravar. Nulo e vazio passam direto. */
    String protect(String plain);

    /** Valor gravado → texto puro. Valores legados sem proteção passam direto. */
    String reveal(String stored);
}
