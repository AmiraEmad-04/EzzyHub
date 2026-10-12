package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * FR-10.1 Comprehensive Verification Report
 *
 * The full output of the matching pipeline. Contains:
 *   - Coverage summary (implemented / partial / missing counts + percentage)
 *   - Per-requirement match results with matched code features
 *   - Processing metadata (timing, chunk counts, algorithm weights)
 *
 * Used by the frontend to render the color-coded status table.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MatchReport {

    // ── Report identity ───────────────────────────────────────────────────────

    /** Unique report ID (UUID) */
    private String reportId;

    /** ISO-8601 timestamp when matching ran */
    private Instant timestamp;

    // ── Coverage summary (FR-7.3) ─────────────────────────────────────────────

    /** Total number of requirements processed */
    private int totalRequirements;

    /**
     * Requirements classified as IMPLEMENTED (score ≥ implementedThreshold).
     * FR-7.3.1
     */
    private int implementedCount;

    /**
     * Requirements classified as PARTIAL (partialThreshold ≤ score < implementedThreshold).
     * FR-7.3.1
     */
    private int partialCount;

    /**
     * Requirements classified as MISSING (score < partialThreshold).
     * FR-7.3.1
     */
    private int missingCount;

    /**
     * Coverage percentage: implementedCount / totalRequirements × 100.
     * FR-7.3.2 — rounded to 1 decimal place.
     */
    private double coveragePercent;

    // ── Code metrics ──────────────────────────────────────────────────────────

    /** Number of Java files processed by AST chunker */
    private int totalJavaFiles;

    /** Total method/class chunks extracted from the codebase */
    private int totalCodeChunks;

    // ── Algorithm parameters (shown for reproducibility) ─────────────────────

    /** Weight applied to cosine similarity (default: 0.65) */
    private double cosineWeight;

    /** Weight applied to Jaccard similarity (default: 0.35) */
    private double jaccardWeight;

    /** Score threshold above which a requirement is IMPLEMENTED (default: 0.80) */
    private double implementedThreshold;

    /** Score threshold above which a requirement is PARTIAL (default: 0.40) */
    private double partialThreshold;

    /** How many top-matching chunks are included per requirement */
    private int topKChunks;

    // ── Timing ────────────────────────────────────────────────────────────────

    /** Total wall-clock time from request to response, in milliseconds */
    private long processingTimeMs;

    /** Time spent calling Python /ast-chunk-zip, in milliseconds */
    private long chunkingTimeMs;

    /** Time spent calling Python /preprocess-and-embed × N requirements, in ms */
    private long embeddingTimeMs;

    /** Time spent in the Java matching engine (cosine + Jaccard matrix), in ms */
    private long matchingTimeMs;

    // ── Per-requirement results (FR-10.1 Requirement Coverage Section) ────────

    /**
     * Full match results for every requirement.
     * Each entry contains: reqId, reqText, status, score, topChunks.
     * Ordered as: MISSING → PARTIAL → IMPLEMENTED (worst to best, for easy review).
     */
    private List<RequirementMatchResult> requirementMatches;

    // ── Warnings / notes ─────────────────────────────────────────────────────

    /**
     * Non-fatal warnings from the pipeline
     * (e.g. "3 requirements had empty keywords", "Python reranker not loaded").
     */
    private List<String> warnings;

    // ── Frontend-compatibility aliases ───────────────────────────────────────
    // The per-requirement rows live in `requirementMatches`. Some frontend table
    // code reads a different key. These expose the SAME list under common names
    // so the table renders regardless of which key it reads. (Serialized only.)

    @com.fasterxml.jackson.annotation.JsonProperty("results")
    public List<RequirementMatchResult> getResults() {
        return requirementMatches;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("matches")
    public List<RequirementMatchResult> getMatches() {
        return requirementMatches;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("requirements")
    public List<RequirementMatchResult> getRequirementsAlias() {
        return requirementMatches;
    }
}
