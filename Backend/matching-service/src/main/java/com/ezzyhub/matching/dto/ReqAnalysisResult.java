package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Subset of Requirement-Analyzer's AnalysisResult (POST /api/v1/requirements/analyze[/batch]).
 * We read only what matching needs: clean id/text, keywords, and the sentence embedding
 * (embedded by the SAME Python /embed the code chunks use, so the vector space matches).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReqAnalysisResult {
    private String status;              // "VALID" | "INVALID"
    private String errorMessage;
    private String reqId;
    private String reqText;
    private String originalInput;
    private List<String> keywords;
    private List<String> actionVerbs;
    private List<Double> sentenceEmbedding;   // same space as code chunk embeddings
    private List<String> warnings;
}
