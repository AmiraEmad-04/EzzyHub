package com.ezzyhub.requirementanalyzer.nlp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PHASE 1 - STEP 2: Basic Format Validation
 *
 * Fast sanity checks before calling the expensive AI validator.
 * Rejects obviously invalid inputs immediately.
 *
 * Checks:
 *   2.1  Minimum word count (≥ 5 words)
 *   2.2  Has alphabetic characters
 *   2.3  Not just numbers or special characters
 */
@Slf4j
@Component
public class BasicFormatValidator {

    @Value("${nlp.min-word-count:5}")
    private int minWordCount;

    /**
     * Validates the cleaned text (after ID has been stripped).
     *
     * @param text cleaned requirement text (no ID prefix)
     * @return null if valid, error message string if invalid
     */
    public String validate(String text) {
        if (text == null || text.isBlank()) {
            return "Requirement text is empty after ID extraction";
        }

        // 2.1 Minimum word count
        String[] words = text.trim().split("\\s+");
        if (words.length < minWordCount) {
            log.debug("Basic validation failed (2.1): too short ({} words)", words.length);
            return String.format("Requirement is too short (%d words). Minimum is %d words.",
                    words.length, minWordCount);
        }

        // 2.2 Must contain alphabetic characters
        boolean hasAlpha = text.chars().anyMatch(Character::isAlphabetic);
        if (!hasAlpha) {
            log.debug("Basic validation failed (2.2): no alphabetic characters");
            return "Requirement must contain alphabetic characters";
        }

        // 2.3 Not just numbers/special characters (at least 3 alpha-only words, per spec)
        long alphaWordCount = java.util.Arrays.stream(words)
                .filter(w -> w.matches("[a-zA-Z]+"))
                .count();
        if (alphaWordCount < 3) {
            log.debug("Basic validation failed (2.3): mostly numbers/special chars ({} alpha words)", alphaWordCount);
            return "Requirement must contain at least 3 meaningful words, not just numbers or symbols";
        }

        log.debug("Basic validation passed for: '{}'", text);
        return null; // null means valid
    }
}
