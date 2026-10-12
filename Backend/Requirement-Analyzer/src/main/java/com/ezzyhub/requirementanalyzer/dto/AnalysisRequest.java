package com.ezzyhub.requirementanalyzer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for the Requirement Analysis endpoint.
 */
@Data
public class AnalysisRequest {

    @NotBlank(message = "Requirement text must not be blank")
    private String requirementText;

    /**
     * If true, skips the AI semantic validation step (Phase 1, Step 3).
     * Useful for batch processing where AI calls are expensive.
     */
    private boolean skipAiValidation = false;
}
