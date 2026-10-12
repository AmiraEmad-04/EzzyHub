package com.ezzyhub.requirementanalyzer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Shared logic for any provider that speaks the OpenAI-compatible chat
 * completions protocol (POST {path}, Authorization: Bearer {key}, response
 * shaped as choices[0].message.content). Groq and OpenAI both implement
 * this contract identically today — subclasses only supply identity
 * (base URL, path, api key, model, display name).
 */
public abstract class AbstractOpenAiChatClient implements LlmClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    protected AbstractOpenAiChatClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    protected abstract String baseUrl();
    protected abstract String path();
    protected abstract String apiKey();
    protected abstract String model();

    @Override
    public boolean isConfigured() {
        return apiKey() != null && !apiKey().isBlank();
    }

    @Override
    public String complete(String prompt) throws LlmClientException {
        Map<String, Object> requestBody = Map.of(
                "model", model(),
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.1,
                "max_tokens", 256
        );

        WebClient client = webClientBuilder
                .baseUrl(baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + apiKey())
                .build();

        String rawResponse;
        try {
            rawResponse = client.post()
                    .uri(path())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new LlmClientException(
                    providerName() + " HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new LlmClientException(providerName() + " request failed: " + e.getMessage(), e);
        }

        return extractContent(rawResponse);
    }

    private String extractContent(String rawResponse) throws LlmClientException {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new LlmClientException(providerName() + " returned an empty response", null);
        }
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                throw new LlmClientException(providerName() + " response missing choices: " + rawResponse, null);
            }
            return choices.get(0).path("message").path("content").asText("");
        } catch (LlmClientException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmClientException(providerName() + " returned unparseable JSON: " + e.getMessage(), e);
        }
    }
}
