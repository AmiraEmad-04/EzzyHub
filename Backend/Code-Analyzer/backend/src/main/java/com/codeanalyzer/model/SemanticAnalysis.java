package com.codeanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Project-level summary — kept minimal.
 * The real semantic output is in AnalysisResult.fileAnalyses[].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SemanticAnalysis {

    private String projectType;
    private String mainPurpose;
    private String technicalStack;
    private String summary;
    private String rawResponse;

    public String getProjectType()   { return projectType; }
    public void setProjectType(String v)   { this.projectType = v; }

    public String getMainPurpose()   { return mainPurpose; }
    public void setMainPurpose(String v)   { this.mainPurpose = v; }

    public String getTechnicalStack(){ return technicalStack; }
    public void setTechnicalStack(String v){ this.technicalStack = v; }

    public String getSummary()       { return summary; }
    public void setSummary(String v)       { this.summary = v; }

    public String getRawResponse()   { return rawResponse; }
    public void setRawResponse(String v)   { this.rawResponse = v; }
}