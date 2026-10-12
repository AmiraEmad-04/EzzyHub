package com.example.ezzyhubai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS configuration for the AI-Suggestions service.
 *
 * Replaces the previous class-level @CrossOrigin("*") on RequirementController.
 * Allowed origins are configurable via {@code cors.allowed-origins} (comma list),
 * same convention as matching-service/Code-Analyzer/auth-service. The default
 * "*" preserves local-dev convenience; this API is credential-less so a
 * wildcard origin is safe here.
 */
@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        // Only the HTTP methods actually exposed by this API are allowed cross-origin.
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
