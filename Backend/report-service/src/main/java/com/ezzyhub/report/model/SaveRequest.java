package com.ezzyhub.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload the frontend POSTs to /api/v1/history when saving an analysis.
 * All heavy JSON fields are optional; the service saves whatever is present.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SaveRequest {

    private String projectName;
    private String analysisType;   // CODE_ANALYSIS | REQUIREMENT_MATCH | SONAR | COMBINED

    // Summary counters (for list view without parsing JSON)
    private Integer totalRequirements;
    private Integer implementedCount;
    private Integer partialCount;
    private Integer missingCount;
    private Double  coveragePercentage;
    private Integer totalSourceFiles;
    private Integer analyzedFileCount;
    private String  status;

    // Raw result payloads (can be null if not yet available)
    private Object codeAnalysisResult;   // serialised to JSON string
    private Object matchResult;          // serialised to JSON string
    private Object sonarResult;          // serialised to JSON string
    private Object aiSuggestions;        // serialised to JSON string

    // ── Getters / Setters ─────────────────────────────────────────────────

    public String getProjectName()               { return projectName; }
    public void setProjectName(String v)         { this.projectName = v; }

    public String getAnalysisType()              { return analysisType; }
    public void setAnalysisType(String v)        { this.analysisType = v; }

    public Integer getTotalRequirements()        { return totalRequirements; }
    public void setTotalRequirements(Integer v)  { this.totalRequirements = v; }

    public Integer getImplementedCount()         { return implementedCount; }
    public void setImplementedCount(Integer v)   { this.implementedCount = v; }

    public Integer getPartialCount()             { return partialCount; }
    public void setPartialCount(Integer v)       { this.partialCount = v; }

    public Integer getMissingCount()             { return missingCount; }
    public void setMissingCount(Integer v)       { this.missingCount = v; }

    public Double getCoveragePercentage()        { return coveragePercentage; }
    public void setCoveragePercentage(Double v)  { this.coveragePercentage = v; }

    public Integer getTotalSourceFiles()         { return totalSourceFiles; }
    public void setTotalSourceFiles(Integer v)   { this.totalSourceFiles = v; }

    public Integer getAnalyzedFileCount()        { return analyzedFileCount; }
    public void setAnalyzedFileCount(Integer v)  { this.analyzedFileCount = v; }

    public String getStatus()                    { return status; }
    public void setStatus(String v)              { this.status = v; }

    public Object getCodeAnalysisResult()        { return codeAnalysisResult; }
    public void setCodeAnalysisResult(Object v)  { this.codeAnalysisResult = v; }

    public Object getMatchResult()               { return matchResult; }
    public void setMatchResult(Object v)         { this.matchResult = v; }

    public Object getSonarResult()               { return sonarResult; }
    public void setSonarResult(Object v)         { this.sonarResult = v; }

    public Object getAiSuggestions()             { return aiSuggestions; }
    public void setAiSuggestions(Object v)       { this.aiSuggestions = v; }
}
