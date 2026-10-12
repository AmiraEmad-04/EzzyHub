package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Summary DTO returned by Requirement Analyzer GET /api/v1/requirements.
 *
 * Matches RequirementSummaryDto from the Requirement Analyzer service.
 * Note: sentenceEmbedding is NOT included in the summary endpoint —
 * the Matching Service re-embeds requirements via Python /preprocess-and-embed.
 *
 * keywords and actionVerbs are stored as JSON strings in the DB
 * (e.g. "[\"user\",\"create\",\"account\"]") and are parsed by the
 * MatchingService before use.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredRequirementDto {

    private Long id;
    private String reqId;
    private String originalText;
    private String cleanText;       // ID-stripped, lowercased text → used for embedding
    private String keywords;        // JSON string: "[\"user\",\"create\",...]"
    private String actionVerbs;     // JSON string
    private String tokens;          // JSON string
    private String validationStatus;
    private Double validationConfidence;
    private String createdAt;       // ISO-8601 string
}
