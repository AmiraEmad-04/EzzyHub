package com.ezzyhub.requirementanalyzer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Active only when ai.provider=openai. Same OpenAI-compatible chat
 * completions protocol as Groq, different host/model/key.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "provider", havingValue = "openai")
public class OpenAiLlmClient extends AbstractOpenAiChatClient {

    private static final String BASE_URL = "https://api.openai.com";
    private static final String PATH = "/v1/chat/completions";

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.api.model:gpt-4o-mini}")
    private String model;

    public OpenAiLlmClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        super(webClientBuilder, objectMapper);
    }

    @Override
    public String providerName() {
        return "OpenAI";
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
