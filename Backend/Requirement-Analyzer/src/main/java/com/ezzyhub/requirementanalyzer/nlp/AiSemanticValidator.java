package com.ezzyhub.requirementanalyzer.nlp;

import com.ezzyhub.requirementanalyzer.dto.ValidationResult;
import com.ezzyhub.requirementanalyzer.llm.LlmClient;
import com.ezzyhub.requirementanalyzer.llm.LlmClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiSemanticValidator {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    // FIX BUG-4: Log a clear, visible warning at startup if the API key is missing.
    // The old code silently sent blank Authorization headers and got 401 errors that
    // were swallowed as "AI validation unavailable". Now the operator knows immediately.
    @jakarta.annotation.PostConstruct
    public void checkApiKey() {
        if (!llmClient.isConfigured()) {
            log.warn("╔══════════════════════════════════════════════════════════════════╗");
            log.warn("║  WARNING: {} API key is not set!                                  ║", llmClient.providerName());
            log.warn("║  AI semantic validation (Step 3) will be SKIPPED for all calls.  ║");
            log.warn("╚══════════════════════════════════════════════════════════════════╝");
        } else {
            log.info("✓ {} configured for AI semantic validation", llmClient.providerName());
        }
    }

    private static final String PROMPT_TEMPLATE =
            "You are a requirements engineering expert. Analyze the software requirement below.\n\n" +
                    "Requirement: \"%s\"\n\n" +
                    "Respond with ONLY a raw JSON object. No markdown. No backticks. No explanation. Only JSON.\n\n" +
                    "Use exactly this structure:\n" +
                    "{\"isRequirement\":true,\"isClear\":true,\"hasMultiple\":false,\"confidence\":0.9,\"reason\":\"\"}\n\n" +
                    "Rules:\n" +
                    "- isRequirement: true if this describes a specific system behavior or constraint\n" +
                    "- isClear: true if it is unambiguous and specific enough to implement\n" +
                    "- hasMultiple: true if it bundles 2+ distinct requirements in one sentence\n" +
                    "- confidence: 0.0-1.0 score for how valid and clear the requirement is\n" +
                    "- reason: brief explanation if invalid, otherwise empty string";

    public ValidationResult validate(String requirementText) {
        if (!llmClient.isConfigured()) {
            log.warn("{} not configured. Skipping AI validation.", llmClient.providerName());
            return buildSkippedResult("AI validation skipped (" + llmClient.providerName() + " API key not configured)");
        }

        try {
            String prompt = String.format(PROMPT_TEMPLATE, requirementText);
            String content = llmClient.complete(prompt);

            log.debug("{} extracted text: {}", llmClient.providerName(), content);
            return parseResponse(content);

        } catch (LlmClientException e) {
            log.error("{} AI validation failed: {}", llmClient.providerName(), e.getMessage());
            return buildFallbackResult("AI validation unavailable: " + e.getMessage());
        }
    }

    private ValidationResult parseResponse(String content) throws LlmClientException {
        if (content == null || content.isBlank()) {
            log.warn("{} returned blank content", llmClient.providerName());
            return buildFallbackResult("Blank content from AI");
        }

        String text = content.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("{} returned unparseable JSON: {}", llmClient.providerName(), e.getMessage());
            return buildFallbackResult("Unparseable AI response");
        }

        boolean isRequirement = parsed.path("isRequirement").asBoolean(true);
        boolean isClear       = parsed.path("isClear").asBoolean(true);
        boolean hasMultiple   = parsed.path("hasMultiple").asBoolean(false);
        double  confidence    = parsed.path("confidence").asDouble(0.5);
        String  reason        = parsed.path("reason").asText("");

        // FIX: evaluate each condition independently so the reason message covers
        // the actual failing condition rather than only the last one checked.
        // Old: "valid = isRequirement && isClear && !hasMultiple" caused the reason
        // to always point at the last failing branch even when an earlier one failed.
        boolean valid = isRequirement && isClear && !hasMultiple;

        // Determine the primary failure reason in priority order
        String failReason = null;
        if (!valid) {
            failReason = buildReason(isRequirement, isClear, hasMultiple, reason);
        }

        log.info("AI Validation result -> valid={}, confidence={}, isRequirement={}, isClear={}, hasMultiple={}, reason={}",
                valid, confidence, isRequirement, isClear, hasMultiple, reason);

        return ValidationResult.builder()
                .valid(valid)
                .confidence(confidence)
                .reason(failReason)
                .multipleReqs(hasMultiple)
                .build();
    }

    private ValidationResult buildSkippedResult(String reason) {
        return ValidationResult.builder()
                .valid(true).confidence(0.5).reason(reason).multipleReqs(false).build();
    }

    private ValidationResult buildFallbackResult(String reason) {
        return ValidationResult.builder()
                .valid(true).confidence(0.5).reason(reason).multipleReqs(false).build();
    }

    private String buildReason(boolean isReq, boolean isClear, boolean hasMultiple, String aiReason) {
        if (!isReq)      return "Text does not appear to be a software requirement";
        if (hasMultiple) return "Text contains multiple requirements — split them into individual requirements";
        if (!isClear)    return "Requirement is too vague or ambiguous to implement";
        return aiReason.isBlank() ? "Failed AI semantic validation" : aiReason;
    }
}
