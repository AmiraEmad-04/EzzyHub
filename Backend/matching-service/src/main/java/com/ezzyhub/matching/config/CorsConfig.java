package com.ezzyhub.matching.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS configuration for the matching service.
 *
 * SECURITY (OWASP A05 Security Misconfiguration / A01 Broken Access Control):
 * The previous configuration combined {@code allowedOriginPatterns("*")} with
 * {@code allowCredentials(true)}. That combination makes Spring reflect the
 * caller's Origin header back and emit {@code Access-Control-Allow-Credentials:
 * true}, which lets ANY website issue credentialed cross-origin requests to this
 * service. This API is credential-less (no cookies/session — nothing to send),
 * so credentials are now disabled, which removes the dangerous reflect-any-origin
 * behaviour while keeping ordinary cross-origin access working for the frontend.
 *
 * Allowed origins are configurable via {@code cors.allowed-origins} (comma list)
 * so production can lock this down to the real frontend host without a code change.
 * The default "*" preserves the prior dev convenience but is now SAFE because
 * credentials are off (a wildcard ACAO can never carry credentials).
 */
@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Credential-less API: never combine credentials with a wildcard/reflected origin.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
