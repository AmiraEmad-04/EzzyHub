package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Full response from Python POST /batch-preprocess-and-embed (NEW v2.2).
 *
 * Maps to Python BatchPreprocessResponse:
 *   total   → total
 *   success → success
 *   failed  → failed
 *   results → results
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchPreprocessResponse {

    private int total;
    private int success;
    private int failed;
    private List<SingleEmbedResult> results;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SingleEmbedResult {
        private int index;

        @JsonProperty("req_id")
        private String reqId;                         // echoed back from "REQ-ID||text" prefix

        private List<String> tokens;
        private List<String> keywords;

        @JsonProperty("action_verbs")
        private List<String> actionVerbs;

        private List<String> warnings;

        @JsonProperty("sentence_embedding")
        private List<Double> sentenceEmbedding;       // 384-dim SBERT

        @JsonProperty("word_embeddings")
        private Map<String, List<Double>> wordEmbeddings;

        @JsonProperty("oov_keywords")
        private List<String> oovKeywords;

        private String error;                         // non-null if this item failed
    }
}
