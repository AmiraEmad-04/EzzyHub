package com.ezzyhub.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Response from Python POST /preprocess-and-embed.
 *
 * Maps to Python PreprocessEmbedResponse:
 *   tokens              → tokens
 *   keywords            → keywords
 *   action_verbs        → actionVerbs
 *   warnings            → warnings
 *   sentence_embedding  → sentenceEmbedding  (384-dim)
 *   word_embeddings     → wordEmbeddings      (384-dim per keyword — BGE-small)
 *   oov_keywords        → oovKeywords         (always empty with BGE, kept for compat)
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PreprocessEmbedResponse {

    private List<String> tokens;
    private List<String> keywords;

    @JsonProperty("action_verbs")
    private List<String> actionVerbs;

    private List<String> warnings;

    @JsonProperty("sentence_embedding")
    private List<Double> sentenceEmbedding;   // 384-dim SBERT

    @JsonProperty("word_embeddings")
    private Map<String, List<Double>> wordEmbeddings;  // BGE per keyword

    @JsonProperty("oov_keywords")
    private List<String> oovKeywords;
}
