package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A requirement as submitted in the POST /api/v1/match request body.
 *
 * Both fields are optional independently:
 *   - reqId   can be auto-generated if missing
 *   - reqText is the text to embed and match
 *
 * If the full requirements JSON array is omitted from the request,
 * the matching service will fetch all requirements from the
 * Requirement Analyzer DB (GET :8081/api/v1/requirements).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequirementInputDto {

    /** Requirement ID (e.g. "FR-2.1.1"). Auto-generated if null. */
    private String reqId;

    /** The full requirement text to embed and match. Required. */
    @NotBlank(message = "reqText must not be blank")
    private String reqText;
}
