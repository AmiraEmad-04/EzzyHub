package com.ezzyhub.requirementanalyzer.nlp;

import com.ezzyhub.requirementanalyzer.repository.RequirementRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 1 - STEP 1: Flexible Format Detection & ID Extraction
 *
 * FIX (Critical Issue #3): AtomicInteger counter is now seeded from the
 * database maximum on startup, so REQ-AUTO-N IDs never collide after a
 * server restart.
 *
 * Handles all 5 requirement ID formats:
 *   1.1  REQ-XXX-N  (e.g. REQ-USER-1)
 *   1.2  RE-N       (e.g. RE-5)
 *   1.3  N.         (e.g. 1. Users can login)
 *   1.4  [N]        (e.g. [10] Display products)
 *   1.5  No ID      → Auto-generate REQ-AUTO-N (counter seeded from DB on startup)
 */
@Slf4j
@Component
public class RequirementIdExtractor {

    // Pattern 1.1: REQ-XXX-N
    private static final Pattern REQ_FULL = Pattern.compile(
            "^(REQ-[A-Z]+-\\d+)\\s*[:\\-]?\\s*", Pattern.CASE_INSENSITIVE);

    // Pattern 1.2: RE-N
    private static final Pattern RE_SHORT = Pattern.compile(
            "^(RE-\\d+)\\s*[:\\-]?\\s*", Pattern.CASE_INSENSITIVE);

    // Pattern 1.3: N.
    private static final Pattern NUMBER_DOT = Pattern.compile(
            "^(\\d+)\\.\\s+");

    // Pattern 1.4: [N]
    private static final Pattern BRACKET_NUMBER = Pattern.compile(
            "^\\[(\\d+)]\\s*");

    // Pattern to extract trailing number from auto-IDs like REQ-AUTO-7
    private static final Pattern AUTO_ID_COUNTER = Pattern.compile("-(\\d+)$");

    @Value("${nlp.auto-id-prefix:REQ-AUTO}")
    private String autoIdPrefix;

    private final AtomicInteger autoCounter = new AtomicInteger(1);
    private final RequirementRepository requirementRepository;

    public RequirementIdExtractor(RequirementRepository requirementRepository) {
        this.requirementRepository = requirementRepository;
    }

    /**
     * FIX: Seed the counter from the highest existing REQ-AUTO-N value in DB.
     * Called once after Spring context finishes wiring all beans.
     */
    @PostConstruct
    public void seedCounterFromDatabase() {
        try {
            requirementRepository.findAll().stream()
                    .map(r -> r.getReqId())
                    .filter(id -> id != null && id.startsWith(autoIdPrefix + "-"))
                    .mapToInt(id -> {
                        Matcher m = AUTO_ID_COUNTER.matcher(id);
                        return m.find() ? Integer.parseInt(m.group(1)) : 0;
                    })
                    .max()
                    .ifPresent(max -> {
                        autoCounter.set(max + 1);
                        log.info("Auto-ID counter seeded from DB: next = {}-{}", autoIdPrefix, max + 1);
                    });
        } catch (Exception e) {
            log.warn("Could not seed auto-ID counter from DB (DB may not be ready yet): {}", e.getMessage());
        }
    }

    /**
     * Extracts the requirement ID from raw input.
     * Returns a 2-element array: [extractedId, cleanedText]
     */
    public String[] extractIdAndText(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return new String[]{generateAutoId(), ""};
        }

        String trimmed = rawInput.trim();

        // 1.1 REQ-XXX-N
        Matcher m1 = REQ_FULL.matcher(trimmed);
        if (m1.find()) {
            log.debug("Format 1.1 matched: {}", m1.group(1));
            return new String[]{m1.group(1).toUpperCase(), trimmed.substring(m1.end()).trim()};
        }

        // 1.2 RE-N
        Matcher m2 = RE_SHORT.matcher(trimmed);
        if (m2.find()) {
            log.debug("Format 1.2 matched: {}", m2.group(1));
            return new String[]{m2.group(1).toUpperCase(), trimmed.substring(m2.end()).trim()};
        }

        // 1.3 N.
        Matcher m3 = NUMBER_DOT.matcher(trimmed);
        if (m3.find()) {
            log.debug("Format 1.3 matched: {}", m3.group(1));
            return new String[]{m3.group(1), trimmed.substring(m3.end()).trim()};
        }

        // 1.4 [N]
        Matcher m4 = BRACKET_NUMBER.matcher(trimmed);
        if (m4.find()) {
            log.debug("Format 1.4 matched: {}", m4.group(1));
            return new String[]{m4.group(1), trimmed.substring(m4.end()).trim()};
        }

        // 1.5 No ID → auto-generate
        String autoId = generateAutoId();
        log.debug("Format 1.5: No ID found. Generated: {}", autoId);
        return new String[]{autoId, trimmed};
    }

    private String generateAutoId() {
        return autoIdPrefix + "-" + autoCounter.getAndIncrement();
    }
}
