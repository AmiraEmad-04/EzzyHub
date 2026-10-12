package com.ezzyhub.requirementanalyzer.nlp;

import com.ezzyhub.requirementanalyzer.dto.ValidationResult;
import com.ezzyhub.requirementanalyzer.service.PythonNlpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Phase 1 Step 3 — AI Semantic Validation.
 *
 * VALIDATION CHAIN (tries each in order until one succeeds):
 *   1. HF Inference API (Mistral-7B) — if USE_HF_VALIDATOR=true and HF_TOKEN set
 *   2. Groq (llama-3.1-8b-instant)  — if GROQ_API_KEY set
 *   3. Rule-based fallback           — always works, no API needed
 *
 * This ensures the system degrades gracefully rather than failing hard
 * when an external AI service is unavailable or rate-limited.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSemanticValidator {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;
    private final PythonNlpClient   pythonNlpClient;

    @Value("${groq.api.key:}")
    private String groqApiKey;

    @Value("${groq.api.model:llama-3.1-8b-instant}")
    private String groqModel;

    @Value("${hf.token:}")
    private String hfToken;

    @Value("${use.hf.validator:false}")
    private boolean useHfValidator;

    @jakarta.annotation.PostConstruct
    public void logConfig() {
        boolean groqOk = groqApiKey != null && !groqApiKey.isBlank();
        boolean hfOk   = hfToken    != null && !hfToken.isBlank();
        if (!groqOk && !hfOk) {
            log.warn("No AI validation API key configured (GROQ_API_KEY or HF_TOKEN). "
                   + "Using rule-based fallback only.");
        } else {
            log.info("AI Validator: HF={}, Groq={}, preferHF={}",
                    hfOk ? "configured" : "NOT SET",
                    groqOk ? "configured" : "NOT SET",
                    useHfValidator);
        }
    }

    public ValidationResult validate(String requirementText) {
        // Try HF Inference API first (if preferred and configured)
        if (useHfValidator) {
            ValidationResult hfResult = tryHfValidator(requirementText);
            if (hfResult != null) return hfResult;
        }

        // Try Groq
        ValidationResult groqResult = tryGroq(requirementText);
        if (groqResult != null) return groqResult;

        // Fallback: rule-based
        return ruleBasedValidation(requirementText);
    }

    // ── HF Inference API ─────────────────────────────────────────────────────

    private ValidationResult tryHfValidator(String text) {
        if (hfToken == null || hfToken.isBlank()) return null;
        try {
            PythonNlpClient.ValidateResult r = pythonNlpClient.validateViaHF(text);
            log.info("HF validation: valid={}, confidence={}", r.valid(), r.confidence());
            return ValidationResult.builder()
                    .valid(r.valid())
                    .confidence(r.confidence())
                    .reason(r.reason())
                    .multipleReqs(r.multipleReqs())
                    .build();
        } catch (Exception e) {
            log.warn("HF validator failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Groq ─────────────────────────────────────────────────────────────────

    private static final String GROQ_PROMPT =
            "You are a requirements engineering expert. Analyze this software requirement: \"%s\"\n\n" +
            "Respond with ONLY a raw JSON object. No markdown. No backticks. Start directly with {.\n\n" +
            "{\"isRequirement\":true,\"isClear\":true,\"hasMultiple\":false,\"confidence\":0.9,\"reason\":\"\"}\n\n" +
            "Rules: isRequirement=true if system behavior/constraint; isClear=true if specific enough to implement; " +
            "hasMultiple=true if 2+ distinct requirements; confidence=0.0-1.0; reason=brief if invalid else empty.";

    private ValidationResult tryGroq(String text) {
        if (groqApiKey == null || groqApiKey.isBlank()) return null;
        try {
            String prompt = String.format(GROQ_PROMPT, text);
            Map<String, Object> body = Map.of(
                    "model",       groqModel,
                    "messages",    List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.1,
                    "max_tokens",  256);

            WebClient client = webClientBuilder.baseUrl("https://api.groq.com")
                    .defaultHeader("Authorization", "Bearer " + groqApiKey)
                    .defaultHeader("Content-Type",  "application/json")
                    .build();

            String raw = client.post()
                    .uri("/openai/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(20))
                    .block();

            JsonNode root    = objectMapper.readTree(raw);
            String   content = root.path("choices").get(0).path("message").path("content").asText();
            return parseGroqResponse(content);
        } catch (WebClientResponseException e) {
            log.warn("Groq HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString().substring(0, 100));
            return null;
        } catch (Exception e) {
            log.warn("Groq validator failed: {}", e.getMessage());
            return null;
        }
    }

    private ValidationResult parseGroqResponse(String content) {
        try {
            String json = content.trim();
            // Strip markdown fences
            if (json.startsWith("```")) json = json.replaceAll("```[a-z]*", "").trim();
            int s = json.indexOf('{'), e = json.lastIndexOf('}');
            if (s < 0 || e < 0) return null;
            JsonNode j = objectMapper.readTree(json.substring(s, e + 1));
            return ValidationResult.builder()
                    .valid(      j.path("isRequirement").asBoolean(true) && j.path("isClear").asBoolean(true))
                    .confidence( j.path("confidence").asDouble(0.7))
                    .reason(     j.path("reason").asText(""))
                    .multipleReqs(j.path("hasMultiple").asBoolean(false))
                    .build();
        } catch (Exception e) {
            log.warn("Groq response parse failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Rule-based fallback ───────────────────────────────────────────────────

    private ValidationResult ruleBasedValidation(String text) {
        String[] words = text.toLowerCase().split("\\s+");
        boolean hasAction = java.util.Arrays.stream(words).anyMatch(
                w -> java.util.Set.of("allow","enable","provide","validate","verify",
                        "generate","display","store","retrieve","detect","classify",
                        "match","analyze","accept","reject","notify","deny","prevent",
                        "support","ensure","create","delete","update","read","upload",
                        "download","import","export").contains(w));
        boolean longEnough = words.length >= 5;
        double confidence  = (hasAction && longEnough) ? 0.75 : 0.35;
        return ValidationResult.builder()
                .valid(hasAction && longEnough)
                .confidence(confidence)
                .reason(confidence < 0.5 ? "Insufficient length or no action verb detected" : "")
                .multipleReqs(false)
                .build();
    }
}
