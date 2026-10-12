package com.ezzyhub.requirementanalyzer.matching;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Request body for POST /api/v1/match.
 *
 * The caller supplies:
 *   - requirementIds: list of reqIds already stored in MySQL by the
 *     requirement-analyzer pipeline (e.g. ["FR-1.1.1", "FR-1.1.2", ...]).
 *     Pass empty list to match ALL stored requirements.
 *
 * The ZIP file is accepted as a @RequestParam MultipartFile — see MatchController.
 */
@Data
public class MatchRequest {

    /**
     * Requirement IDs to match against.
     * Empty list = match every requirement in the database.
     */
    private List<String> requirementIds;

    /**
     * Whether to skip AI semantic validation when the requirement
     * analyzer runs (passed through to the analyzer facade).
     * Default true here because requirements were already validated
     * when first submitted.
     */
    private boolean skipAiValidation = true;
}
