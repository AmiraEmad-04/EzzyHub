package com.ezzyhub.requirementanalyzer.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Internal result from AI Semantic Validation (Phase 1, Step 3).
 */
@Data
@Builder
public class ValidationResult {

    private boolean valid;
    private double confidence;      // 0.0 - 1.0
    private String reason;          // Human-readable explanation if invalid
    private boolean multipleReqs;   // True if text contains multiple requirements
}
