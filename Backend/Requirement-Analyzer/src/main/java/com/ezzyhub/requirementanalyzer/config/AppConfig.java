package com.ezzyhub.requirementanalyzer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Spring application configuration.
 * Registers shared beans for HTTP client, JSON mapper, and Swagger docs.
 */
@Configuration
public class AppConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        // FIX: Increase maxInMemorySize from 2MB to 16MB.
        // Word2Vec responses for large keyword lists can exceed 2MB:
        //   100 keywords × 300 floats × 8 bytes/float = ~240 KB per request,
        //   but with JSON overhead and synonym expansion (300+ keywords) the
        //   payload easily approaches or exceeds 2MB.
        return WebClient.builder()
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) // 16MB
                );
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public OpenAPI openApiConfig() {
        return new OpenAPI()
                .info(new Info()
                        .title("EzzyHub Requirement Analyzer API")
                        .description("""
                                AI-assisted Requirement Analysis Module for the EzzyHub Platform.
                                Implements a 13-step NLP pipeline covering:
                                validation, preprocessing, keyword extraction, and semantic embedding.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("EzzyHub Team")
                                .email("team@ezzyhub.com"))
                        .license(new License().name("Private — Graduation Project")));
    }
}
