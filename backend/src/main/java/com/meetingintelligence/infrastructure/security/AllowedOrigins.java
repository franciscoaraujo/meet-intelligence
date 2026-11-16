package com.meetingintelligence.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Origens do frontend autorizadas a chamar a API e o WebSocket (spec 007, RF-15).
 * {@code app.security.allowed-origins} = lista separada por vírgula; padrão: o Vite local.
 */
@Component
public class AllowedOrigins {

    private final List<String> origins;

    public AllowedOrigins(@Value("${app.security.allowed-origins:http://localhost:5173}") String raw) {
        this.origins = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty() && !"*".equals(o))
                .toList();
    }

    public List<String> list() {
        return origins;
    }

    public String[] array() {
        return origins.toArray(String[]::new);
    }
}
