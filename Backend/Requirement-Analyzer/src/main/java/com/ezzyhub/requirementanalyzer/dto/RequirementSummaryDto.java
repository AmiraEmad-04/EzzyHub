package com.ezzyhub.requirementanalyzer.dto;

import com.ezzyhub.requirementanalyzer.model.Requirement;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Safe DTO for GET /requirements and GET /requirements/{reqId}.
 *
 * FIX Issue #2: LocalDateTime is serialized as a formatted string, not
 * a raw Java object — avoids the Jackson LocalDateTimeSerializer 500 error.
 *
 * FIX Issue #14: sentenceEmbedding blob is intentionally excluded from the
 * list response. If a caller needs the full embedding, they should fetch it
 * from the database by reqId and deserialize the stored JSON TEXT column.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequirementSummaryDto(
        Long id,
        String reqId,
        String originalText,
        String cleanText,
        String keywords,       // stored as JSON string — caller parses if needed
        String actionVerbs,    // stored as JSON string
        String tokens,         // stored as JSON string
        String validationStatus,
        Double validationConfidence,
        String createdAt       // ISO-8601 string, never raw LocalDateTime
) {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public static RequirementSummaryDto from(Requirement r) {
        return new RequirementSummaryDto(
                r.getId(),
                r.getReqId(),
                r.getOriginalText(),
                r.getCleanText(),
                r.getKeywords(),
                r.getActionVerbs(),
                r.getTokens(),
                r.getValidationStatus(),
                r.getValidationConfidence(),
                r.getCreatedAt() != null ? r.getCreatedAt().format(FMT) : null
        );
    }
}
