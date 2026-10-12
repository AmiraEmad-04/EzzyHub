package com.ezzyhub.requirementanalyzer.matching;

/** FR-7.2.1 classification labels. */
public enum MatchStatus {
    IMPLEMENTED,   // score >= 0.80
    PARTIAL,       // 0.40 <= score < 0.80
    MISSING        // score < 0.40
}
