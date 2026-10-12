package com.codeanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResult {

    private String id;
    private String status;             // success | partial | error
    private Instant timestamp;
    private String projectName;
    private long processingTimeMs;
    private String llmProvider;
    private String llmModel;

    /** High-level project summary */
    private SemanticAnalysis projectSummary;

    /** THE main output: semantic meaning of every analyzed file */
    private List<FileSemanticAnalysis> fileAnalyses;

    /** Structural metadata (tree, stats, file list) */
    private ProjectStructure structure;

    /** Total source files found (including ones not sent to LLM) */
    private int totalSourceFiles;

    /** How many files were actually analyzed by LLM */
    private int analyzedFileCount;

    private String error;

    public AnalysisResult() { this.timestamp = Instant.now(); }

    public String getId()              { return id; }
    public void setId(String v)        { this.id = v; }
    public String getStatus()          { return status; }
    public void setStatus(String v)    { this.status = v; }
    public Instant getTimestamp()      { return timestamp; }
    public void setTimestamp(Instant v){ this.timestamp = v; }
    public String getProjectName()     { return projectName; }
    public void setProjectName(String v){ this.projectName = v; }
    public long getProcessingTimeMs()  { return processingTimeMs; }
    public void setProcessingTimeMs(long v){ this.processingTimeMs = v; }
    public String getLlmProvider()     { return llmProvider; }
    public void setLlmProvider(String v){ this.llmProvider = v; }
    public String getLlmModel()        { return llmModel; }
    public void setLlmModel(String v)  { this.llmModel = v; }
    public SemanticAnalysis getProjectSummary(){ return projectSummary; }
    public void setProjectSummary(SemanticAnalysis v){ this.projectSummary = v; }
    public List<FileSemanticAnalysis> getFileAnalyses(){ return fileAnalyses; }
    public void setFileAnalyses(List<FileSemanticAnalysis> v){ this.fileAnalyses = v; }
    public ProjectStructure getStructure(){ return structure; }
    public void setStructure(ProjectStructure v){ this.structure = v; }
    public int getTotalSourceFiles()   { return totalSourceFiles; }
    public void setTotalSourceFiles(int v){ this.totalSourceFiles = v; }
    public int getAnalyzedFileCount()  { return analyzedFileCount; }
    public void setAnalyzedFileCount(int v){ this.analyzedFileCount = v; }
    public String getError()           { return error; }
    public void setError(String v)     { this.error = v; }
}