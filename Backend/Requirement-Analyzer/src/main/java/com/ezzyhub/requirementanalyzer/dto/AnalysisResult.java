package com.ezzyhub.requirementanalyzer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Full output of the Requirement Analysis Pipeline.
 *
 * FIX: Changed warnings field from null-default to always present list.
 * The service calls result.getWarnings().add(...) after build — this NPE'd
 * when warnings was null (JsonInclude.NON_NULL caused it to not be initialized).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResult {

    // ---- Status ----
    private String status;               // "VALID" | "INVALID"
    private String errorMessage;         // Set if status = INVALID

    // ---- Identity ----
    private String reqId;                // Extracted or auto-generated ID
    private String reqText;              // Clean text (ID stripped)
    private String originalInput;        // Raw user input

    // ---- Phase 2: Preprocessed Tokens ----
    private List<String> tokens;         // Clean lemmatized tokens

    // ---- Phase 3: Keyword Extraction ----
    private List<String> keywords;       // All extracted + expanded keywords
    private List<String> actionVerbs;    // Action verbs only (VB tags)

    // ---- Phase 4: Embeddings ----
    private float[] sentenceEmbedding;   // 384-dim vector
    private Map<String, float[]> wordEmbeddings; // Per-keyword 300-dim vectors

    // ---- Metadata ----
    private Double validationConfidence; // 0.0-1.0 from AI validation
    private List<String> warnings;       // Non-blocking warnings
}
