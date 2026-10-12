package com.ezzyhub.requirementanalyzer.matching;

import com.ezzyhub.requirementanalyzer.model.Requirement;
import com.ezzyhub.requirementanalyzer.service.PythonNlpClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core matching engine — FR-7.
 *
 * v2 PIPELINE (3 stages):
 *
 *   Stage 1 — Bi-encoder retrieval  (Jaccard + SBERT cosine)
 *     Fast O(n×m) scan across all code features.
 *     Produces a scored, sorted list of candidate features per requirement.
 *     Takes top-K (default 10) into Stage 2.
 *
 *   Stage 2 — Cross-encoder re-ranking  (NEW — Hugging Face)
 *     Sends (requirement_text, candidate_semantic_text) pairs to the Python
 *     /rerank endpoint (cross-encoder/ms-marco-MiniLM-L-6-v2).
 *     The cross-encoder reads both texts together → better relevance scores.
 *     This corrects false positives (shared keywords, different meaning) and
 *     false negatives (paraphrases with low Jaccard).
 *
 *   Stage 3 — Status classification  (FR-7.2.1 thresholds)
 *     Uses the blended bi-encoder score OR the cross-encoder score,
 *     whichever produced the highest-ranked result.
 *
 * SCORING FORMULA (Stage 1):
 *   blended = (JACCARD_W × jaccard) + (COSINE_W × cosine)
 *   Jaccard (0.35) — exact keyword overlap (precise for tech terms)
 *   SBERT cosine (0.65) — semantic similarity (catches synonyms/paraphrases)
 *
 * CROSS-ENCODER NORMALIZATION:
 *   The cross-encoder outputs a raw logit score (not bounded 0-1).
 *   We sigmoid-normalize it so it can be blended with the bi-encoder score.
 *
 * THRESHOLDS (FR-7.2.1):
 *   >= 0.80 → IMPLEMENTED
 *   0.40-0.79 → PARTIAL
 *   < 0.40 → MISSING
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingService {

    // Weight tuning (bi-encoder stage)
    static final double JACCARD_WEIGHT = 0.35;
    static final double COSINE_WEIGHT  = 0.65;

    // FR-7.2.1 thresholds
    static final double IMPLEMENTED_THRESHOLD = 0.80;
    static final double PARTIAL_THRESHOLD      = 0.40;

    // How many top candidates to pass to the cross-encoder (Stage 2)
    @Value("${matching.rerank.top-k:10}")
    private int rerankTopK;

    // Whether to use cross-encoder reranking (can be disabled for speed)
    @Value("${matching.rerank.enabled:true}")
    private boolean rerankEnabled;

    private final ObjectMapper    objectMapper;
    private final PythonNlpClient pythonNlpClient;

    /**
     * Matches a single Requirement against all code features.
     * Runs the 3-stage pipeline: Jaccard+SBERT → cross-encoder → classify.
     */
    public MatchResult match(Requirement requirement, List<CodeFeature> features) {
        List<String> reqKeywords = parseKeywords(requirement.getKeywords());
        List<Float>  reqEmbedding = parseEmbedding(requirement.getSentenceEmbedding());
        String       reqText      = requirement.getCleanText();

        // ── Stage 1: Bi-encoder retrieval ─────────────────────────────────
        List<ScoredFeature> scored = new ArrayList<>(features.size());
        for (CodeFeature feature : features) {
            if (feature.getKeywords() == null || feature.getEmbedding() == null) continue;
            double jaccard = jaccardSimilarity(reqKeywords, feature.getKeywords());
            double cosine  = cosineSimilarity(reqEmbedding, feature.getEmbedding());
            double blended = JACCARD_WEIGHT * jaccard + COSINE_WEIGHT * cosine;
            scored.add(new ScoredFeature(feature, blended, jaccard, cosine));
        }
        scored.sort(Comparator.comparingDouble(ScoredFeature::score).reversed());

        if (scored.isEmpty()) {
            return missingResult(requirement);
        }

        // ── Stage 2: Cross-encoder re-ranking ─────────────────────────────
        List<ScoredFeature> finalRanked = scored;
        if (rerankEnabled && rerankTopK > 0 && reqText != null && !reqText.isBlank()) {
            try {
                List<ScoredFeature> topK = scored.subList(0, Math.min(rerankTopK, scored.size()));
                List<String> candidateTexts = topK.stream()
                        .map(sf -> sf.feature().getSemanticText())
                        .filter(t -> t != null && !t.isBlank())
                        .collect(Collectors.toList());

                if (!candidateTexts.isEmpty()) {
                    List<PythonNlpClient.RerankResult> reranked =
                            pythonNlpClient.rerank(reqText, candidateTexts, rerankTopK);

                    // Merge cross-encoder scores back into ScoredFeature list
                    List<ScoredFeature> rerankedList = new ArrayList<>();
                    for (PythonNlpClient.RerankResult rr : reranked) {
                        if (rr.index() < topK.size()) {
                            ScoredFeature sf = topK.get(rr.index());
                            // Blend: 60% cross-encoder (normalized) + 40% bi-encoder
                            double normalizedCE = sigmoid(rr.score());
                            double finalScore   = 0.6 * normalizedCE + 0.4 * sf.score();
                            rerankedList.add(new ScoredFeature(
                                    sf.feature(), finalScore, sf.jaccard(), sf.cosine()));
                        }
                    }
                    // Append any features beyond top-K unchanged
                    if (scored.size() > rerankTopK) {
                        rerankedList.addAll(scored.subList(rerankTopK, scored.size()));
                    }
                    rerankedList.sort(Comparator.comparingDouble(ScoredFeature::score).reversed());
                    finalRanked = rerankedList;
                    log.debug("Req {} reranked: top bi={:.3f} → top CE={:.3f}",
                            requirement.getReqId(),
                            scored.get(0).score(),
                            finalRanked.get(0).score());
                }
            } catch (Exception e) {
                // Non-fatal: fall back to bi-encoder ranking
                log.warn("Cross-encoder rerank failed for {}: {} — using bi-encoder ranking",
                        requirement.getReqId(), e.getMessage());
            }
        }

        // ── Stage 3: Classify best match ──────────────────────────────────
        ScoredFeature best = finalRanked.get(0);
        MatchStatus status = classify(best.score());

        log.debug("Req {} → score={} ({}) file={}",
                requirement.getReqId(),
                String.format("%.3f", best.score()),
                status,
                best.feature().getFilePath());

        return MatchResult.builder()
                .requirementId(requirement.getReqId())
                .requirementText(requirement.getCleanText())
                .score(round(best.score()))
                .jaccardScore(round(best.jaccard()))
                .cosineScore(round(best.cosine()))
                .status(status)
                .matchedFilePath(best.feature().getFilePath())
                .matchedMethodName(best.feature().getMethodName())
                .matchedSemanticText(best.feature().getSemanticText())
                .build();
    }

    // ── Similarity algorithms ─────────────────────────────────────────────────

    double jaccardSimilarity(List<String> a, List<String> b) {
        if (a == null || b == null || (a.isEmpty() && b.isEmpty())) return 0.0;
        Set<String> setA = a.stream().map(String::toLowerCase).collect(Collectors.toSet());
        Set<String> setB = b.stream().map(String::toLowerCase).collect(Collectors.toSet());
        Set<String> intersection = new HashSet<>(setA); intersection.retainAll(setB);
        Set<String> union        = new HashSet<>(setA); union.addAll(setB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        int len = Math.min(a.size(), b.size());
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < len; i++) {
            double ai = a.get(i), bi = b.get(i);
            dot += ai * bi; normA += ai * ai; normB += bi * bi;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-10 ? 0.0 : dot / denom;
    }

    /** Sigmoid normalizes cross-encoder logit to (0, 1). */
    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MatchStatus classify(double score) {
        if (score >= IMPLEMENTED_THRESHOLD) return MatchStatus.IMPLEMENTED;
        if (score >= PARTIAL_THRESHOLD)     return MatchStatus.PARTIAL;
        return MatchStatus.MISSING;
    }

    private double round(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private MatchResult missingResult(Requirement req) {
        return MatchResult.builder()
                .requirementId(req.getReqId())
                .requirementText(req.getCleanText())
                .score(0.0).jaccardScore(0.0).cosineScore(0.0)
                .status(MatchStatus.MISSING).build();
    }

    private List<String> parseKeywords(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { log.warn("Could not parse keywords JSON: {}", e.getMessage()); return List.of(); }
    }

    private List<Float> parseEmbedding(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { log.warn("Could not parse embedding JSON: {}", e.getMessage()); return List.of(); }
    }

    // Internal value object
    private record ScoredFeature(CodeFeature feature, double score, double jaccard, double cosine) {}
}
