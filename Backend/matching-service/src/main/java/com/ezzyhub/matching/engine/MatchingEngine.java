package com.ezzyhub.matching.engine;

import com.ezzyhub.matching.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FR-7: Requirement-to-Code Matching Engine
 *
 * Implements the professor's recommendation:
 *   "Many-to-Many strategy: [Requirement A] → Embed → Cosine Similarity Matrix ←
 *    [Code Chunk 1] → Embed → Threshold Filter → Mapping"
 *
 * Algorithm for each requirement R:
 *   1. For every code chunk C:
 *        cosine  = cosineSimilarity(R.embedding, C.embedding)      [0.0, 1.0]
 *        jaccard = jaccardSimilarity(R.keywords,  C.keywords)      [0.0, 1.0]
 *        score   = cosineWeight × cosine + jaccardWeight × jaccard [0.0, 1.0]
 *
 *   2. Sort all chunks by score descending → take top-K
 *
 *   3. best_score = top chunks[0].score
 *      gap_margin  = (top1.score - top2.score) / stdev(all candidate scores)
 *
 *   4. Classify (FR-7.2.1, v2.4 margin-aware):
 *        best_score ≥ implementedThreshold AND gap_margin ≥ marginImplementedThreshold → IMPLEMENTED
 *        best_score ≥ partialThreshold      AND gap_margin ≥ marginPartialThreshold     → PARTIAL
 *        otherwise                                                                      → MISSING
 *
 *      WHY THE MARGIN CHECK: a raw score threshold alone can't tell a genuine
 *      match from "best of a noisy lot" — some requirements produce a higher
 *      overall similarity floor across ALL candidate files just from shared
 *      vocabulary/boilerplate. Requiring the top match to clearly separate
 *      from its closest competitor (not just clear an absolute bar) filters
 *      that noise back out. Calibrated and verified by simulation against
 *      pure noise at the real population size (226 candidate files per
 *      requirement) before shipping — see margin_test_v2.py.
 *
 * WHY BOTH COSINE AND JACCARD?
 *   - Cosine captures deep semantic similarity (context-aware via SBERT)
 *   - Jaccard captures lexical overlap (exact keyword matches — high precision)
 *   - Together they are more robust: a requirement with unusual phrasing that
 *     still shares keywords with code will score higher than cosine alone would give.
 *
 * WHY NOT JUST MAX COSINE?
 *   - SBERT cosine alone can miss good matches when code uses abbreviations
 *     or domain-specific names that aren't in the SBERT training set.
 *   - Jaccard with synonym-expanded keywords (from Python SynonymExpander)
 *     bridges this gap.
 */
@Slf4j
@Component
public class MatchingEngine {

    @Value("${matching.cosine.weight:0.65}")
    private double cosineWeight;

    @Value("${matching.jaccard.weight:0.35}")
    private double jaccardWeight;

    @Value("${matching.threshold.implemented:0.80}")
    private double implementedThreshold;

    @Value("${matching.threshold.partial:0.40}")
    private double partialThreshold;

    /**
     * v2.4 — Minimum standout margin (top1-vs-top2 score gap, normalized by
     * this requirement's own score stdev) required, IN ADDITION to the raw
     * score threshold, to classify as IMPLEMENTED.
     *
     * CALIBRATED VALUE: 2.0. Verified via simulation (margin_test_v2.py) that
     * at the real population size (226 candidate files/requirement), pure
     * noise produces a gap-margin with mean ~0.32 and 99th percentile ~1.37 —
     * so 2.0 sits safely above the noise band while still passing genuine
     * standout matches (tested: a real standout with best=0.55 vs a noisy
     * floor around 0.18-0.20 produces gap-margin ~2.3).
     */
    @Value("${matching.margin.implemented:2.0}")
    private double marginImplementedThreshold;

    /**
     * Same idea, lower bar, required for PARTIAL.
     * CALIBRATED VALUE: 1.0 — near the mean noise gap (~0.32) but enough
     * above it to cut noise-driven false PARTIALs by ~96% in simulation
     * (500 trials → only 18 false PARTIALs, 0 false IMPLEMENTEDs).
     */
    @Value("${matching.margin.partial:1.0}")
    private double marginPartialThreshold;

    @Value("${matching.top-k-chunks:5}")
    private int topKChunks;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Run the full many-to-many matching algorithm.
     *
     * @param requirements list of requirements with embeddings + keywords
     * @param codeChunks   list of code chunks with embeddings + keywords (from AST chunker)
     * @return list of match results, sorted: MISSING → PARTIAL → IMPLEMENTED
     */
    public List<RequirementMatchResult> match(
            List<EmbeddedRequirement> requirements,
            List<CodeChunkDto> codeChunks) {

        if (requirements.isEmpty()) {
            log.warn("MatchingEngine: no requirements to match — returning empty list");
            return Collections.emptyList();
        }
        if (codeChunks.isEmpty()) {
            log.warn("MatchingEngine: no code chunks — all requirements will be MISSING");
            return requirements.stream()
                    .map(req -> RequirementMatchResult.builder()
                            .reqId(req.reqId())
                            .reqText(req.reqText())
                            .status("MISSING")
                            .score(0.0)
                            .cosineScore(0.0)
                            .jaccardScore(0.0)
                            .requirementKeywords(req.keywords())
                            .topChunks(Collections.emptyList())
                            .build())
                    .toList();
        }

        log.info("MatchingEngine: {} requirements × {} chunks (cosine_w={}, jaccard_w={})",
                requirements.size(), codeChunks.size(), cosineWeight, jaccardWeight);
        long t0 = System.currentTimeMillis();

        // Pre-convert chunk embeddings to double[] for faster cosine computation
        // (avoids repeated unboxing inside the inner loop)
        List<double[]> chunkEmbeddings = codeChunks.stream()
                .map(c -> toDoubleArray(c.getEmbedding()))
                .toList();

        List<Set<String>> chunkKeywordSets = codeChunks.stream()
                .map(c -> toKeywordSet(c.getKeywords()))
                .toList();

        // ── Many-to-Many matching (parallelized) ──────────────────────────────
        // Each requirement is scored independently against the same read-only
        // chunk data (codeChunks / chunkEmbeddings / chunkKeywordSets are never
        // mutated here, and matchOneRequirement keeps all its working state
        // local), so scoring the requirements in parallel is safe and cuts the
        // dominant O(requirements × chunks) cosine/jaccard cost across CPU cores.
        // parallelStream preserves encounter order into the collected list, and
        // the list is sorted immediately after regardless, so output is identical
        // to the previous sequential version — only faster.
        List<RequirementMatchResult> results = requirements.parallelStream()
                .map(req -> matchOneRequirement(req, codeChunks, chunkEmbeddings, chunkKeywordSets))
                .collect(Collectors.toList());

        // Sort: MISSING → PARTIAL → IMPLEMENTED (worst first for easy review)
        results.sort(Comparator.comparingInt(r -> statusOrder(r.getStatus())));

        long elapsed = System.currentTimeMillis() - t0;
        logSummary(results, elapsed);
        return results;
    }

    /**
     * Returns the algorithm weights and thresholds used, for inclusion in the report.
     */
    public Map<String, Object> getAlgorithmParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cosineWeight", cosineWeight);
        params.put("jaccardWeight", jaccardWeight);
        params.put("implementedThreshold", implementedThreshold);
        params.put("partialThreshold", partialThreshold);
        params.put("marginImplementedThreshold", marginImplementedThreshold);
        params.put("marginPartialThreshold", marginPartialThreshold);
        params.put("topKChunks", topKChunks);
        return params;
    }

    // ── Single-requirement matching ───────────────────────────────────────────

    private RequirementMatchResult matchOneRequirement(
            EmbeddedRequirement req,
            List<CodeChunkDto> chunks,
            List<double[]> chunkEmbeddings,
            List<Set<String>> chunkKeywordSets) {

        double[] reqEmb = toDoubleArray(req.embedding());
        Set<String> reqKws = toKeywordSet(req.keywords());

        // Score every chunk
        List<ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunkDto chunk = chunks.get(i);

            double cosine  = cosineSimilarity(reqEmb, chunkEmbeddings.get(i));
            double jaccard = jaccardSimilarity(reqKws, chunkKeywordSets.get(i));
            double combined = cosineWeight * cosine + jaccardWeight * jaccard;

            scored.add(new ScoredChunk(chunk, cosine, jaccard, combined));
        }

        // ── Pick the single best-scoring chunk PER FILE first ──────────────────
        // BUG FIX: previously we sorted ALL chunks by score and sliced top-K
        // directly. A class with many methods could flood the top-K with its
        // own chunks, crowding out the one chunk from a DIFFERENT file that
        // was actually the correct match — capping recall regardless of how
        // high topKChunks was set. Grouping by file first guarantees every
        // file gets one fair shot at its best chunk before we truncate.
        Map<String, ScoredChunk> bestPerFile = new LinkedHashMap<>();
        for (ScoredChunk sc : scored) {
            String filePath = sc.chunk().getFilePath();
            ScoredChunk existing = bestPerFile.get(filePath);
            if (existing == null || sc.combined() > existing.combined()) {
                bestPerFile.put(filePath, sc);
            }
        }

        List<ScoredChunk> rankedFiles = new ArrayList<>(bestPerFile.values());
        rankedFiles.sort(Comparator.comparingDouble(ScoredChunk::combined).reversed());

        // Sort by combined score descending, take top-K distinct files
        List<ScoredChunk> topK = rankedFiles.subList(0, Math.min(topKChunks, rankedFiles.size()));

        // Best score = top-1 combined score
        double bestScore    = topK.isEmpty() ? 0.0 : topK.get(0).combined();
        double bestCosine   = topK.isEmpty() ? 0.0 : topK.get(0).cosine();
        double bestJaccard  = topK.isEmpty() ? 0.0 : topK.get(0).jaccard();

        // ── v2.4: Standout margin — is the best match a REAL standout, or just ──
        // the "best of a noisy lot"? Uses the GAP between the top-1 and top-2
        // candidate files, normalized by this requirement's own score stdev.
        //
        // WHY GAP, NOT (best - populationMean)/stdev:
        //   The first version of this fix used distance from the population
        //   MEAN. Testing that against the REAL population size (226 files
        //   per requirement) exposed a critical flaw: under PURE NOISE with
        //   n=226, the max of the distribution is ALREADY ~2.8 std devs above
        //   the mean on average (basic extreme value statistics — the more
        //   candidates you have, the higher the max drifts from the mean by
        //   chance alone). A margin threshold low enough to mean anything
        //   (e.g. 1.0) would have been cleared by noise almost every time.
        //
        //   The gap between the TOP-1 and TOP-2 candidate is far more robust
        //   to population size — under the same pure-noise simulation, the
        //   average gap is only ~0.32 std devs, 99th percentile ~1.37. A
        //   genuine standout match separates clearly from its closest
        //   competitor; noise decays smoothly with small gaps between
        //   consecutive order statistics. Verified via simulation before
        //   shipping (see margin_test_v2.py) — at the calibrated thresholds
        //   below, 500 trials of pure noise at n=226 produced ZERO false
        //   IMPLEMENTED classifications.
        double populationMean   = mean(rankedFiles);
        double populationStdDev = stdDev(rankedFiles, populationMean);
        double standoutMargin;
        if (rankedFiles.size() <= 1 || populationStdDev <= 1e-9) {
            // Only one candidate file (or zero spread) — there's nothing to
            // compare against, so the margin check is meaningless here.
            // Fall back to the raw score threshold alone by reporting a margin
            // that always clears the bar, rather than one that can never clear it.
            standoutMargin = Math.max(marginImplementedThreshold, marginPartialThreshold);
        } else {
            double secondBestScore = rankedFiles.get(1).combined();
            standoutMargin = (bestScore - secondBestScore) / populationStdDev;
        }

        String status = classify(bestScore, standoutMargin);
        String confidenceNote = buildConfidenceNote(bestScore, standoutMargin,
                populationMean, populationStdDev, status);

        // Build ChunkMatchResult list
        List<ChunkMatchResult> chunkResults = topK.stream()
                .map(sc -> ChunkMatchResult.builder()
                        .filePath(sc.chunk().getFilePath())
                        .className(sc.chunk().getClassName())
                        .methodName(sc.chunk().getMethodName())
                        .chunkType(sc.chunk().getChunkType())
                        .chunkText(sc.chunk().getChunkText())
                        .lineStart(sc.chunk().getLineStart())
                        .score(round4(sc.combined()))
                        .cosineScore(round4(sc.cosine()))
                        .jaccardScore(round4(sc.jaccard()))
                        .chunkKeywords(sc.chunk().getKeywords())
                        .build())
                .toList();

        log.debug("Req [{}]: best={} ({}) → {} (cosine={}, jaccard={})",
                req.reqId(), round4(bestScore), topK.isEmpty() ? "—"
                        : topK.get(0).chunk().getClassName() + "."
                        + topK.get(0).chunk().getMethodName(),
                status, round4(bestCosine), round4(bestJaccard));

        return RequirementMatchResult.builder()
                .reqId(req.reqId())
                .reqText(req.reqText())
                .status(status)
                .score(round4(bestScore))
                .cosineScore(round4(bestCosine))
                .jaccardScore(round4(bestJaccard))
                .requirementKeywords(req.keywords())
                .topChunks(chunkResults)
                .standoutMargin(round4(standoutMargin))
                .populationMeanScore(round4(populationMean))
                .populationStdDev(round4(populationStdDev))
                .confidenceNote(confidenceNote)
                .build();
    }

    // ── Cosine Similarity ─────────────────────────────────────────────────────

    /**
     * Cosine similarity between two dense vectors.
     *
     *   cos(a, b) = (a · b) / (‖a‖ × ‖b‖)
     *
     * Properties:
     *   - Returns 0.0 for zero vectors (graceful no-match)
     *   - Clamped to [0.0, 1.0] — SBERT rarely produces negatives but we guard it
     *   - Dimension mismatch → 0.0 (defensive)
     */
    private static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0.0;
        if (a.length != b.length) {
            // Shouldn't happen if both come from the same SBERT model (384-dim)
            // but we guard it defensively
            return 0.0;
        }

        double dot   = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) return 0.0;   // zero vector → no similarity
        double cos = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(0.0, Math.min(1.0, cos));         // clamp to [0, 1]
    }

    // ── Jaccard Similarity ────────────────────────────────────────────────────

    /**
     * Jaccard similarity between two keyword sets.
     *
     *   jaccard(A, B) = |A ∩ B| / |A ∪ B|
     *
     * Uses the synonym-expanded keyword lists from the Python NLP pipeline,
     * so "login" matches "authenticate", "register" matches "signup", etc.
     *
     * Returns 0.0 if either set is empty (graceful fallback to cosine only).
     */
    private static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;

        // Intersection size
        long intersectionSize = a.stream().filter(b::contains).count();
        // Union size = |A| + |B| - |A∩B|
        long unionSize = a.size() + b.size() - intersectionSize;

        if (unionSize == 0) return 0.0;
        return (double) intersectionSize / unionSize;
    }

    // ── Classification (FR-7.2.1, v2.4 margin-aware) ──────────────────────────

    /**
     * v2.4: classification now requires BOTH the raw score AND the standout
     * margin to clear their respective bars. A file that scores above the
     * raw threshold purely because this requirement's whole candidate pool
     * runs hot (generic vocabulary, shared boilerplate) — but doesn't stand
     * out from that pool — is no longer classified as a confident match.
     *
     * This directly targets the population-level noise floor problem: fixing
     * the embedding model improved per-pair discrimination but raised the
     * WHOLE score distribution, which alone doesn't help fixed-threshold
     * precision. Requiring a genuine standout (z-score) on top of the raw
     * score is what actually filters that noise back out.
     */
    private String classify(double score, double margin) {
        if (score >= implementedThreshold && margin >= marginImplementedThreshold) {
            return "IMPLEMENTED";
        }
        if (score >= partialThreshold && margin >= marginPartialThreshold) {
            return "PARTIAL";
        }
        return "MISSING";
    }

    private static double mean(List<ScoredChunk> chunks) {
        if (chunks.isEmpty()) return 0.0;
        double sum = 0.0;
        for (ScoredChunk sc : chunks) sum += sc.combined();
        return sum / chunks.size();
    }

    private static double stdDev(List<ScoredChunk> chunks, double mean) {
        if (chunks.size() < 2) return 0.0;
        double sumSq = 0.0;
        for (ScoredChunk sc : chunks) {
            double diff = sc.combined() - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / (chunks.size() - 1));
    }

    private String buildConfidenceNote(double bestScore, double gapMargin,
                                       double populationMean, double populationStdDev,
                                       String status) {
        String base = String.format(
                "Best match scores %.4f, separated from the next-best candidate by %.2f "
                        + "standard deviations (this requirement's candidate pool: mean=%.4f, "
                        + "std dev=%.4f).",
                bestScore, gapMargin, populationMean, populationStdDev);

        switch (status) {
            case "IMPLEMENTED":
                return base + " Clear standout from its closest competitor — classified "
                        + "IMPLEMENTED with high confidence.";
            case "PARTIAL":
                return base + " Some separation from its closest competitor — classified PARTIAL.";
            default:
                if (bestScore >= partialThreshold) {
                    return base + " Score alone would clear the PARTIAL threshold, but the top "
                            + "match does not meaningfully separate from the next-best candidate — "
                            + "likely noise from shared vocabulary across many files, not a real "
                            + "match. Classified MISSING.";
                }
                return base + " Classified MISSING.";
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static double[] toDoubleArray(List<Double> list) {
        if (list == null || list.isEmpty()) return new double[0];
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i) != null ? list.get(i) : 0.0;
        }
        return arr;
    }

    private static Set<String> toKeywordSet(List<String> keywords) {
        if (keywords == null) return Collections.emptySet();
        return keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    /** Sort order for status: MISSING first (0), PARTIAL (1), IMPLEMENTED (2) */
    private static int statusOrder(String status) {
        return switch (status) {
            case "MISSING"     -> 0;
            case "PARTIAL"     -> 1;
            case "IMPLEMENTED" -> 2;
            default            -> -1;
        };
    }

    private static void logSummary(List<RequirementMatchResult> results, long ms) {
        long imp     = results.stream().filter(r -> "IMPLEMENTED".equals(r.getStatus())).count();
        long partial = results.stream().filter(r -> "PARTIAL".equals(r.getStatus())).count();
        long missing = results.stream().filter(r -> "MISSING".equals(r.getStatus())).count();
        double coverage = results.isEmpty() ? 0 : (imp * 100.0 / results.size());
        log.info("MatchingEngine done in {}ms - IMPLEMENTED={}, PARTIAL={}, MISSING={}, coverage={}%",
                ms, imp, partial, missing, String.format("%.1f", coverage));
    }

    // ── Internal record ───────────────────────────────────────────────────────

    /** Internal holder for a scored chunk (never exposed outside this class). */
    private record ScoredChunk(CodeChunkDto chunk, double cosine, double jaccard, double combined) {}
}