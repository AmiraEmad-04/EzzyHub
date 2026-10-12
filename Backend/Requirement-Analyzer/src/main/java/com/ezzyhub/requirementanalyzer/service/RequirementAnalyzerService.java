package com.ezzyhub.requirementanalyzer.service;

import com.ezzyhub.requirementanalyzer.dto.AnalysisRequest;
import com.ezzyhub.requirementanalyzer.dto.AnalysisResult;
import com.ezzyhub.requirementanalyzer.facade.RequirementAnalyzerFacade;
import com.ezzyhub.requirementanalyzer.model.Requirement;
import com.ezzyhub.requirementanalyzer.repository.RequirementRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service layer for Requirement Analysis.
 *
 * FIX (duplicate text): analyzeAndSave now checks for an existing row by
 * CLEAN TEXT (case-insensitive) BEFORE falling back to reqId lookup.
 * This prevents the same requirement text being stored multiple times
 * as REQ-AUTO-2, REQ-AUTO-10, REQ-AUTO-11 when no explicit ID is given.
 *
 * Deduplication priority:
 *   1. Exact reqId match → UPDATE that row (reqId was explicit, e.g. REQ-AUTH-1)
 *   2. Clean-text match  → UPDATE that row (same text, different auto ID)
 *   3. No match          → INSERT new row
 *
 * FIX (confidence): validationConfidence is null when AI validation was skipped,
 * so the response clearly distinguishes "not run" from a real 0.5 score.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementAnalyzerService {

    private final RequirementAnalyzerFacade analyzerFacade;
    private final RequirementRepository requirementRepository;
    private final ObjectMapper objectMapper;

    /**
     * Wrapper returned to the controller so it knows whether to send
     * 201 Created or 200 OK.
     */
    public record SaveResult(AnalysisResult result, boolean wasUpdated) {}

    @Transactional
    public SaveResult analyzeAndSave(AnalysisRequest request) {
        AnalysisResult result = analyzerFacade.analyze(request);
        boolean wasUpdated = false;

        try {
            Requirement entity = buildEntity(result);

            // ── Deduplication: reqId first, then clean-text ──────────────────
            Optional<Requirement> existing = requirementRepository.findByReqId(result.getReqId());

            if (existing.isEmpty() && result.getReqText() != null && !result.getReqText().isBlank()) {
                // FIX: same text submitted again with a different auto-generated ID
                existing = requirementRepository.findByCleanTextIgnoreCase(result.getReqText());
                if (existing.isPresent()) {
                    // Re-use the original reqId so we don't create another REQ-AUTO-N
                    String originalReqId = existing.get().getReqId();
                    log.info("Duplicate text detected — re-using existing reqId '{}' instead of '{}'",
                            originalReqId, result.getReqId());
                    result.setReqId(originalReqId);
                    entity.setReqId(originalReqId);
                }
            }

            if (existing.isPresent()) {
                entity.setId(existing.get().getId());
                // Preserve original createdAt on update (FIX Issue #12)
                entity.setCreatedAt(existing.get().getCreatedAt());
                wasUpdated = true;
                log.info("Updating existing requirement: {}", result.getReqId());
            } else {
                log.info("Inserting new requirement: {}", result.getReqId());
            }

            requirementRepository.save(entity);

        } catch (Exception e) {
            log.error("Failed to persist requirement {}: {}", result.getReqId(), e.getMessage());
            List<String> warnings = result.getWarnings() != null
                    ? new ArrayList<>(result.getWarnings())
                    : new ArrayList<>();
            warnings.add("Warning: Failed to persist result to database: " + e.getMessage());
            result.setWarnings(warnings);
        }

        return new SaveResult(result, wasUpdated);
    }

    public Optional<Requirement> findByReqId(String reqId) {
        return requirementRepository.findByReqId(reqId);
    }

    public List<Requirement> findAll() {
        return requirementRepository.findAll();
    }

    @Transactional
    public void deleteById(Long id) {
        requirementRepository.deleteById(id);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private Requirement buildEntity(AnalysisResult result) {
        return Requirement.builder()
                .reqId(result.getReqId())
                .originalText(result.getOriginalInput())
                .cleanText(result.getReqText())
                .keywords(toJson(result.getKeywords()))
                .actionVerbs(toJson(result.getActionVerbs()))
                .tokens(toJson(result.getTokens()))
                .sentenceEmbedding(toJson(result.getSentenceEmbedding()))
                .validationStatus(result.getStatus())
                .validationConfidence(result.getValidationConfidence())
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON serialization failed: {}", e.getMessage());
            return "[]";
        }
    }
}