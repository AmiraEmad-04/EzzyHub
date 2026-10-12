package com.codeanalyzer.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Autowired
    private AppConfig appConfig;

    @Bean
    public CorsFilter corsFilter() {
        String allowedOrigins = appConfig.get("CORS_ALLOWED_ORIGINS", "*");

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(false);

        if ("*".equals(allowedOrigins.trim())) {
            config.addAllowedOriginPattern("*");
        } else {
            List<String> origins = Arrays.asList(allowedOrigins.split(","));
            origins.forEach(o -> config.addAllowedOrigin(o.trim()));
        }

        config.addAllowedHeader("*");
        // Only the HTTP methods actually exposed by this API are allowed cross-origin
        // (no endpoint uses PUT/DELETE/PATCH, so there is no legitimate use for allowing them).
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("OPTIONS");
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
