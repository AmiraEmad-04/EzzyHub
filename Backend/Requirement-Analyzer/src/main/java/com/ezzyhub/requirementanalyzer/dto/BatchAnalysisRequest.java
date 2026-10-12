package com.ezzyhub.requirementanalyzer.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Request body for the batch analysis endpoint.
 *
 * FIX Issue #8: The old batch endpoint hard-coded skipAiValidation=true
 * without telling the caller. Now the caller explicitly sets it.
 *
 * Default: skipAiValidation=false (AI runs on every item unless you opt out).
 * Set to true when you have many requirements and want to save Groq API cost.
 */
@Data
public class BatchAnalysisRequest {

    @NotEmpty(message = "Requirements list must not be empty")
    private List<String> requirements;

    /**
     * If true, skips Groq AI semantic validation for every item in the batch.
     * Use this to save API cost when processing large batches.
     * The response will include a warning for each skipped item.
     * Default: false.
     */
    private boolean skipAiValidation = false;
}
