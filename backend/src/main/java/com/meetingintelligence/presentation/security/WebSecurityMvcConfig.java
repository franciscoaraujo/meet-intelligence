package com.meetingintelligence.presentation.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra a guarda central de reunião (spec 007, RF-11). */
@Configuration
public class WebSecurityMvcConfig implements WebMvcConfigurer {

    private final MeetingAccessGuard meetingAccessGuard;

    public WebSecurityMvcConfig(MeetingAccessGuard meetingAccessGuard) {
        this.meetingAccessGuard = meetingAccessGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(meetingAccessGuard).addPathPatterns("/api/v1/meetings/*", "/api/v1/meetings/*/**");
    }
}
