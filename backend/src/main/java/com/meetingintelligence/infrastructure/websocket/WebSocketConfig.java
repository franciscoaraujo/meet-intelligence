package com.meetingintelligence.infrastructure.websocket;

import com.meetingintelligence.infrastructure.security.AllowedOrigins;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompSecurityInterceptor security;
    private final AllowedOrigins allowedOrigins;

    public WebSocketConfig(StompSecurityInterceptor security, AllowedOrigins allowedOrigins) {
        this.security = security;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Broker simples em memória; só o servidor publica (spec 007, RF-09)
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Spec 007 RF-10: só as origens do frontend (antes: qualquer origem)
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.array())
                .withSockJS();
    }

    /** Spec 007 RF-07..RF-09: autenticação no CONNECT, autorização no SUBSCRIBE, SEND recusado. */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(security);
    }
}
