package com.ezzyhub.requirementanalyzer.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Persists a MatchReport to the database.
 *
 * This entity was MISSING in v1 — match reports were computed, returned to the
 * caller, then immediately discarded. Users had no way to retrieve a past report
 * without re-running the entire matching pipeline.
 *
 * The full MatchReport is stored as a JSON blob in report_json so no foreign-key
 * schema is needed for the nested MatchResult list. Individual scalar columns
 * (coverage, counts) are stored separately for quick querying and dashboard stats
 * without deserialising the full blob.
 *
 * Table: verification_reports
 */
@Entity
@Table(name = "verification_reports", indexes = {
        @Index(name = "idx_vr_generated_at", columnList = "generated_at"),
        @Index(name = "idx_vr_project",      columnList = "project_name")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class VerificationReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_name", length = 512)
    private String projectName;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "total_requirements")
    private int totalRequirements;

    @Column(name = "implemented_count")
    private int implementedCount;

    @Column(name = "partial_count")
    private int partialCount;

    @Column(name = "missing_count")
    private int missingCount;

    @Column(name = "coverage_percentage")
    private double coveragePercentage;

    @Column(name = "processing_time_ms")
    private long processingTimeMs;

    /**
     * Full MatchReport serialised as JSON.
     * MEDIUMTEXT in MySQL — can hold up to 16 MB (enough for 100 requirements
     * with full matched code descriptions).
     */
    @Column(name = "report_json", columnDefinition = "MEDIUMTEXT")
    private String reportJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
