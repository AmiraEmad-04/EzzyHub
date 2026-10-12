package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Complete matching result for a single functional requirement.
 *
 * FR-7.2 Automatic Status Classification:
 *   score ≥ 0.80 → IMPLEMENTED  (high confidence)
 *   score 0.40–0.79 → PARTIAL   (partially implemented)
 *   score < 0.40  → MISSING     (not found in code)
 *
 * FR-7.3 Coverage Calculation: aggregated in MatchReport.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RequirementMatchResult {

    /** Requirement identifier (e.g. "FR-2.1.1") */
    private String reqId;

    /** Full requirement text */
    private String reqText;

    /**
     * Classification status: IMPLEMENTED | PARTIAL | MISSING
     * FR-7.2.1
     */
    private String status;

    /**
     * Best combined score across all code chunks.
     * Combined = cosineWeight × max_cosine + jaccardWeight × max_jaccard
     * Rounded to 4 decimal places.
     */
    private double score;

    /**
     * Cosine component of the best match.
     * Shown for transparency and debugging.
     */
    private double cosineScore;

    /**
     * Jaccard component of the best match.
     * Shown for transparency and debugging.
     */
    private double jaccardScore;

    /**
     * Top-K matching code chunks, sorted by score descending.
     * Provides evidence for the classification (FR-10.1 report).
     */
    private List<ChunkMatchResult> topChunks;

    /**
     * Keywords extracted from this requirement by Python NLP pipeline.
     * Shown for transparency — helps explain Jaccard scores.
     */
    private List<String> requirementKeywords;

    /**
     * v2.4 — Standout margin: the GAP between this requirement's top-1 and
     * top-2 candidate file scores, normalized by the stdev of all candidate
     * scores for this requirement (NOT the original mean-distance z-score —
     * that version was tested and found to be cleared by pure noise almost
     * every time at real-world population sizes; see MatchingEngine).
     *
     * WHY THIS EXISTS: a fixed absolute threshold (e.g. score >= 0.50) treats
     * every requirement the same, but some requirements naturally produce a
     * higher overall similarity floor across ALL files (generic vocabulary,
     * shared boilerplate terms) while others don't. A top score that's only
     * marginally ahead of the next-best candidate is much weaker evidence of
     * a real match than one that clearly separates from its closest
     * competitor. This field makes that distinction visible and is now part
     * of classification (see MatchingEngine.classify()), not just informational.
     */
    private double standoutMargin;

    /** Mean combined score across ALL candidate files for this requirement (the noise floor). */
    private double populationMeanScore;

    /** Standard deviation of combined scores across all candidate files for this requirement. */
    private double populationStdDev;

    /**
     * Human-readable explanation of the classification decision — e.g.
     * "Best match scores 0.52, which is 2.1 standard deviations above this
     * requirement's average candidate score (0.21) — confident match."
     * or "Best match scores 0.38, but that is only 0.4 std dev above the
     * noise floor (0.33) — likely a false positive, classified MISSING
     * despite clearing the raw score threshold."
     */
    private String confidenceNote;
}