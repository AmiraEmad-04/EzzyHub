package com.ezzyhub.report.model;

import java.time.Instant;

/** Lightweight summary returned in the history list — no payload blobs. */
public class HistorySummary {

    private Long    id;
    private String  projectName;
    private String  analysisType;
    private Instant createdAt;
    private String  status;

    private Integer totalRequirements;
    private Integer implementedCount;
    private Integer partialCount;
    private Integer missingCount;
    private Double  coveragePercentage;
    private Integer totalSourceFiles;
    private Integer analyzedFileCount;

    /** Indicates which result sections are present */
    private boolean hasCodeAnalysis;
    private boolean hasMatchResult;
    private boolean hasSonarResult;
    private boolean hasAiSuggestions;

    public static HistorySummary from(AnalysisRecord r) {
        HistorySummary s = new HistorySummary();
        s.id                  = r.getId();
        s.projectName         = r.getProjectName();
        s.analysisType        = r.getAnalysisType();
        s.createdAt           = r.getCreatedAt();
        s.status              = r.getStatus();
        s.totalRequirements   = r.getTotalRequirements();
        s.implementedCount    = r.getImplementedCount();
        s.partialCount        = r.getPartialCount();
        s.missingCount        = r.getMissingCount();
        s.coveragePercentage  = r.getCoveragePercentage();
        s.totalSourceFiles    = r.getTotalSourceFiles();
        s.analyzedFileCount   = r.getAnalyzedFileCount();
        s.hasCodeAnalysis     = r.getCodeAnalysisJson()  != null;
        s.hasMatchResult      = r.getMatchResultJson()   != null;
        s.hasSonarResult      = r.getSonarResultJson()   != null;
        s.hasAiSuggestions    = r.getAiSuggestionsJson() != null;
        return s;
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public Long    getId()                 { return id; }
    public String  getProjectName()        { return projectName; }
    public String  getAnalysisType()       { return analysisType; }
    public Instant getCreatedAt()          { return createdAt; }
    public String  getStatus()             { return status; }
    public Integer getTotalRequirements()  { return totalRequirements; }
    public Integer getImplementedCount()   { return implementedCount; }
    public Integer getPartialCount()       { return partialCount; }
    public Integer getMissingCount()       { return missingCount; }
    public Double  getCoveragePercentage() { return coveragePercentage; }
    public Integer getTotalSourceFiles()   { return totalSourceFiles; }
    public Integer getAnalyzedFileCount()  { return analyzedFileCount; }
    public boolean isHasCodeAnalysis()     { return hasCodeAnalysis; }
    public boolean isHasMatchResult()      { return hasMatchResult; }
    public boolean isHasSonarResult()      { return hasSonarResult; }
    public boolean isHasAiSuggestions()    { return hasAiSuggestions; }
}
