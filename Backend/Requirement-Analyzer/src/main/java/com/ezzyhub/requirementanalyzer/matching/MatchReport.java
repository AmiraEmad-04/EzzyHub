package com.ezzyhub.requirementanalyzer.matching;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Full match report returned by POST /api/v1/match.
 * Persisted to the database as a JSON blob so users can retrieve past reports.
 */
@Data
@Builder
public class MatchReport {
    private String            projectName;
    private Instant           generatedAt;

    private List<MatchResult> results;

    // Coverage counters (FR-7.3)
    private int               totalRequirements;
    private int               implementedCount;
    private int               partialCount;
    private int               missingCount;
    private double            coveragePercentage;   // (implemented / total) × 100

    // Metadata
    private long              processingTimeMs;
    private boolean           rerankUsed;           // was cross-encoder applied?
    private String            codeAnalyzerVersion;
}
