package com.ezzyhub.report.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted record of one analysis session.
 * Payload columns use @Lob / TEXT so large JSON is stored without truncation.
 *
 * NEW: was mapped to a table called `analysis_records` in report-service's
 * own (separate, embedded) H2 database. Now mapped to `verification_reports`
 * in the shared MySQL `ezzyhub_db` instead — see the accompanying
 * application.properties change for why.
 */
@Entity
@Table(name = "verification_reports",
       indexes = {
           @Index(name = "idx_user_created", columnList = "userId,createdAt"),
       })
public class AnalysisRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical user identifier — replaced by real auth later */
    @Column(nullable = false, length = 128)
    private String userId;

    /** Human-readable label (project / file name) */
    @Column(length = 512)
    private String projectName;

    /** Type tag: CODE_ANALYSIS | REQUIREMENT_MATCH | SONAR | COMBINED */
    @Column(nullable = false, length = 64)
    private String analysisType;

    /** When the analysis was submitted */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * BUG FIX (history save failing): the shared ezzyhub_db table was originally
     * created by an older version of this entity that had a NOT NULL
     * `generated_at` column. That column is no longer part of this entity, but
     * because Hibernate's ddl-auto=update never drops columns, it lingered in the
     * table as NOT NULL with no default — so every INSERT this service did was
     * rejected by MySQL ("Field 'generated_at' doesn't have a default value"),
     * which surfaced in the UI as "Report complete, but saving to History failed."
     *
     * Re-mapping the column here and auto-populating it on persist makes the
     * INSERT always provide a value, so saving works on the legacy table, a fresh
     * database, or anything in between — without needing a manual ALTER TABLE.
     */
    @Column(name = "generated_at")
    private Instant generatedAt;

    // ── Result counters (for quick display without parsing JSON) ───────────

    private Integer totalRequirements;
    private Integer implementedCount;
    private Integer partialCount;
    private Integer missingCount;
    private Double  coveragePercentage;

    private Integer totalSourceFiles;
    private Integer analyzedFileCount;

    private String  status;          // success | partial | error

    // ── Raw JSON payloads ─────────────────────────────────────────────────
    // LONGTEXT, not TEXT: a real match/analysis result is large. With
    // matching.top-k-chunks=200, a report of ~20 requirements already serializes
    // to a couple of MB — far past MySQL's 64 KB TEXT limit — so saving a real run
    // failed with "Data too long for column 'match_result_json'" and surfaced in the
    // UI as "Report complete, but saving to History failed." LONGTEXT holds up to
    // 4 GB, comfortably covering full results.

    /** Full code analysis JSON from Code-Analyzer */
    @Column(columnDefinition = "LONGTEXT")
    private String codeAnalysisJson;

    /** Full matching result JSON from Requirement-Analyzer */
    @Column(columnDefinition = "LONGTEXT")
    private String matchResultJson;

    /** Sonar analysis JSON from Sonar-Analyzer */
    @Column(columnDefinition = "LONGTEXT")
    private String sonarResultJson;

    /** AI suggestions JSON (future) */
    @Column(columnDefinition = "LONGTEXT")
    private String aiSuggestionsJson;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) this.createdAt = Instant.now();
        // Always populate the legacy generated_at column (see field comment) so
        // the INSERT succeeds even where the column is still NOT NULL.
        if (this.generatedAt == null) this.generatedAt = this.createdAt;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────

    public Long getId()                           { return id; }
    public void setId(Long id)                    { this.id = id; }

    public String getUserId()                     { return userId; }
    public void setUserId(String userId)          { this.userId = userId; }

    public String getProjectName()                { return projectName; }
    public void setProjectName(String projectName){ this.projectName = projectName; }

    public String getAnalysisType()               { return analysisType; }
    public void setAnalysisType(String t)         { this.analysisType = t; }

    public Instant getCreatedAt()                 { return createdAt; }
    public void setCreatedAt(Instant t)           { this.createdAt = t; }

    public Instant getGeneratedAt()               { return generatedAt; }
    public void setGeneratedAt(Instant t)         { this.generatedAt = t; }

    public Integer getTotalRequirements()         { return totalRequirements; }
    public void setTotalRequirements(Integer v)   { this.totalRequirements = v; }

    public Integer getImplementedCount()          { return implementedCount; }
    public void setImplementedCount(Integer v)    { this.implementedCount = v; }

    public Integer getPartialCount()              { return partialCount; }
    public void setPartialCount(Integer v)        { this.partialCount = v; }

    public Integer getMissingCount()              { return missingCount; }
    public void setMissingCount(Integer v)        { this.missingCount = v; }

    public Double getCoveragePercentage()         { return coveragePercentage; }
    public void setCoveragePercentage(Double v)   { this.coveragePercentage = v; }

    public Integer getTotalSourceFiles()          { return totalSourceFiles; }
    public void setTotalSourceFiles(Integer v)    { this.totalSourceFiles = v; }

    public Integer getAnalyzedFileCount()         { return analyzedFileCount; }
    public void setAnalyzedFileCount(Integer v)   { this.analyzedFileCount = v; }

    public String getStatus()                     { return status; }
    public void setStatus(String status)          { this.status = status; }

    public String getCodeAnalysisJson()           { return codeAnalysisJson; }
    public void setCodeAnalysisJson(String v)     { this.codeAnalysisJson = v; }

    public String getMatchResultJson()            { return matchResultJson; }
    public void setMatchResultJson(String v)      { this.matchResultJson = v; }

    public String getSonarResultJson()            { return sonarResultJson; }
    public void setSonarResultJson(String v)      { this.sonarResultJson = v; }

    public String getAiSuggestionsJson()          { return aiSuggestionsJson; }
    public void setAiSuggestionsJson(String v)    { this.aiSuggestionsJson = v; }
}
