package com.ezzyhub.requirementanalyzer.facade;

import com.ezzyhub.requirementanalyzer.dto.AnalysisRequest;
import com.ezzyhub.requirementanalyzer.dto.AnalysisResult;
import com.ezzyhub.requirementanalyzer.dto.ValidationResult;
import com.ezzyhub.requirementanalyzer.exception.InvalidRequirementException;
import com.ezzyhub.requirementanalyzer.nlp.AiSemanticValidator;
import com.ezzyhub.requirementanalyzer.nlp.BasicFormatValidator;
import com.ezzyhub.requirementanalyzer.nlp.RequirementIdExtractor;
import com.ezzyhub.requirementanalyzer.service.PythonNlpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RequirementAnalyzerFacade — Pipeline Orchestrator
 *
 * FIX (confidence): validationConfidence is null when AI validation is skipped.
 * This clearly distinguishes "AI ran and gave 0.5" from "AI never ran".
 * The DTO uses @JsonInclude(NON_NULL) so null confidence is simply omitted
 * from the JSON response (not shown as 0.5 which is misleading).
 *
 * FIX (Word2Vec health): After /embed returns, if ALL word embeddings are zero
 * vectors, a warning is added to tell the caller Word2Vec is not loaded.
 *
 * ═══ PHASE 1: VALIDATION & ID EXTRACTION (Java) ═══
 *   Step 1:  ID Extraction
 *   Step 2:  Basic Format Validation
 *   Step 3:  AI Semantic Validation (or skipped)
 *
 * ═══ PHASES 2+3+4: NLP + EMBEDDING (Python microservice) ═══
 *   POST /preprocess → tokens, keywords, action_verbs
 *   POST /embed      → sentenceEmbedding (384-dim SBERT), wordEmbeddings (300-dim Word2Vec)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequirementAnalyzerFacade {

    private final RequirementIdExtractor idExtractor;
    private final BasicFormatValidator basicValidator;
    private final AiSemanticValidator aiValidator;
    private final PythonNlpClient pythonNlpClient;

    public AnalysisResult analyze(AnalysisRequest request) {
        String rawInput = request.getRequirementText().trim();
        List<String> warnings = new ArrayList<>();

        log.info("=== Starting Requirement Analysis Pipeline ===");
        log.info("Input: '{}'", rawInput);

        // ─────────────────────────────────────────────────────────────
        // PHASE 1 (Java): VALIDATION & ID EXTRACTION
        // ─────────────────────────────────────────────────────────────

        // Step 1: ID Extraction
        log.debug("--- Step 1: ID Extraction ---");
        String[] idAndText = idExtractor.extractIdAndText(rawInput);
        String reqId   = idAndText[0];
        String reqText = idAndText[1];
        log.info("Step 1 ✓  ID='{}', Text='{}'", reqId, reqText);

        // Step 2: Basic Format Validation
        log.debug("--- Step 2: Basic Format Validation ---");
        String basicError = basicValidator.validate(reqText);
        if (basicError != null) {
            log.warn("Step 2 ✗  Basic validation failed: {}", basicError);
            throw new InvalidRequirementException("Basic validation failed: " + basicError);
        }
        log.info("Step 2 ✓  Basic validation passed");

        // Step 3: AI Semantic Validation (or skipped)
        // FIX: confidence is null when skipped — null is serialized as absent field,
        //      not as 0.5 which falsely implies a borderline AI result.
        ValidationResult aiResult;
        Double validationConfidence;

        if (request.isSkipAiValidation()) {
            log.info("Step 3 ⚠  AI validation skipped (skipAiValidation=true in request)");
            aiResult = ValidationResult.builder()
                    .valid(true)
                    .confidence(0.5)   // internal default, NOT propagated to response
                    .reason("AI validation skipped by caller request")
                    .multipleReqs(false)
                    .build();
            validationConfidence = null;   // FIX: null → absent in JSON response
            warnings.add("AI semantic validation was skipped");
        } else {
            log.debug("--- Step 3: AI Semantic Validation ---");
            aiResult = aiValidator.validate(reqText);
            if (!aiResult.isValid()) {
                log.warn("Step 3 ✗  AI validation failed: {}", aiResult.getReason());
                throw new InvalidRequirementException("Semantic validation failed: " + aiResult.getReason());
            }
            validationConfidence = aiResult.getConfidence();
            log.info("Step 3 ✓  AI validation passed (confidence={})", validationConfidence);
            if (aiResult.isMultipleReqs()) {
                warnings.add("Requirement may contain multiple concerns — consider splitting");
            }
        }

        // ─────────────────────────────────────────────────────────────
        // PHASES 2+3 (Python): PREPROCESSING & KEYWORD EXTRACTION
        // ─────────────────────────────────────────────────────────────

        log.debug("--- Phases 2+3: Python microservice /preprocess ---");
        PythonNlpClient.PreprocessResult preprocessResult = pythonNlpClient.preprocess(reqText);

        List<String> tokens      = preprocessResult.tokens();
        List<String> keywords    = preprocessResult.keywords();
        List<String> actionVerbs = preprocessResult.actionVerbs();
        warnings.addAll(preprocessResult.warnings());

        log.info("Phases 2+3 ✓  tokens={}, keywords={}, actionVerbs={}", tokens, keywords, actionVerbs);

        // ─────────────────────────────────────────────────────────────
        // PHASE 4 (Python): SEMANTIC EMBEDDING
        // ─────────────────────────────────────────────────────────────

        log.debug("--- Phase 4: Python microservice /embed ---");
        PythonNlpClient.EmbedResult embedResult = pythonNlpClient.embed(reqText, keywords);

        // [DIAG] Temporary: print vector length and first 5 values to confirm 768 vs 384/zeros
        float[] _diagVec = embedResult.sentenceEmbedding();
        log.info("[DIAG] Python /embed returned: length={}, first5={}",
                _diagVec.length,
                java.util.Arrays.copyOf(_diagVec, Math.min(5, _diagVec.length)));

        float[] sentenceEmbedding           = embedResult.sentenceEmbedding();
        Map<String, float[]> wordEmbeddings = embedResult.wordEmbeddings();

        if (!embedResult.oovKeywords().isEmpty()) {
            warnings.add("Word2Vec OOV keywords (zero vectors used): " + embedResult.oovKeywords());
        }

        // FIX: detect the case where ALL word embeddings are zero (Word2Vec not loaded)
        boolean allZero = !wordEmbeddings.isEmpty() && wordEmbeddings.values().stream()
                .allMatch(vec -> {
                    for (float v : vec) { if (v != 0f) return false; }
                    return true;
                });
        if (allZero && !wordEmbeddings.isEmpty()) {
            warnings.add("Word2Vec model is not loaded — all word embeddings are zero vectors. "
                    + "Check Python service startup logs and ensure the model has been downloaded.");
            log.warn("Phase 4 ⚠  ALL word embeddings are zero — Word2Vec model may not be loaded");
        }

        log.info("Phase 4 ✓  sentence-embedding={}dim, word-embeddings={} keywords",
                sentenceEmbedding.length, wordEmbeddings.size());

        log.info("=== Pipeline Complete: VALID ===");

        return AnalysisResult.builder()
                .status("VALID")
                .reqId(reqId)
                .reqText(reqText)
                .originalInput(rawInput)
                .tokens(tokens)
                .keywords(keywords)
                .actionVerbs(actionVerbs)
                .sentenceEmbedding(sentenceEmbedding)
                .wordEmbeddings(wordEmbeddings)
                .validationConfidence(validationConfidence)   // null if AI was skipped
                .warnings(warnings.isEmpty() ? null : warnings)
                .build();
    }
}