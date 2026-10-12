package com.ezzyhub.requirementanalyzer.matching;

import lombok.Builder;
import lombok.Data;

/**
 * Result for a single Requirement ↔ Code feature match.
 * Returned as part of MatchReport.
 */
@Data
@Builder
public class MatchResult {
    private String      requirementId;
    private String      requirementText;

    /** Blended final score (Jaccard + SBERT cosine ± cross-encoder). 0.0–1.0 */
    private double      score;

    /** Component scores — useful for debugging and report display */
    private double      jaccardScore;
    private double      cosineScore;

    private MatchStatus status;           // IMPLEMENTED | PARTIAL | MISSING

    /** Best-matching code location */
    private String      matchedFilePath;
    private String      matchedMethodName;
    private String      matchedSemanticText;   // truncated code description
}
