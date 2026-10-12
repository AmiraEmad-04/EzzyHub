package com.ezzyhub.requirementanalyzer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Default LlmClient — active unless ai.provider is set to something else.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "provider", havingValue = "groq", matchIfMissing = true)
public class GroqLlmClient extends AbstractOpenAiChatClient {

    private static final String BASE_URL = "https://api.groq.com";
    private static final String PATH = "/openai/v1/chat/completions";

    @Value("${groq.api.key:}")
    private String apiKey;

    @Value("${groq.api.model:llama-3.1-8b-instant}")
    private String model;

    public GroqLlmClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        super(webClientBuilder, objectMapper);
    }

    @Override
    public String providerName() {
        return "Groq";
    }

    @Override
    protected String baseUrl() {
        return BASE_URL;
    }

    @Override
    protected String path() {
        return PATH;
    }

    @Override
    protected String apiKey() {
        return apiKey;
    }

    @Override
    protected String model() {
        return model;
    }
}
