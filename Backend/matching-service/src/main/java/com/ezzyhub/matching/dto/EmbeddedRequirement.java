package com.ezzyhub.matching.dto;

import java.util.List;

/**
 * Internal record: a requirement that has been preprocessed and embedded,
 * ready to be compared against code chunks.
 *
 * Built by MatchingService after calling Python /preprocess-and-embed.
 *
 * @param reqId      requirement identifier (e.g. "FR-2.1.1")
 * @param reqText    original or clean requirement text
 * @param keywords   NLP-extracted + synonym-expanded keyword list
 * @param embedding  384-dim SBERT sentence embedding (from all-MiniLM-L6-v2)
 */
public record EmbeddedRequirement(
        String reqId,
        String reqText,
        List<String> keywords,
        List<Double> embedding
) {}
