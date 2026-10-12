package com.ezzyhub.matching.engine;

import com.ezzyhub.matching.dto.ChunkMatchResult;
import com.ezzyhub.matching.dto.CodeChunkDto;
import com.ezzyhub.matching.dto.EmbeddedRequirement;
import com.ezzyhub.matching.dto.RequirementMatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link MatchingEngine} — the core FR-7 classification algorithm.
 *
 * These tests exist because, at the time of writing, this class (the single
 * most business-critical piece of EzzyHub — it decides IMPLEMENTED / PARTIAL /
 * MISSING for every requirement) had zero automated test coverage anywhere in
 * the codebase, despite the file's own comments documenting a history of
 * subtle, hard-to-notice correctness bugs (file-crowding in top-K selection,
 * noise-vs-standout margin calibration, embedding zero-vector issues).
 */
class MatchingEngineTest {

    /** Weights/thresholds as documented in MatchingEngine's own Javadoc as the calibrated design. */
    private static final double COSINE_WEIGHT = 0.65;
    private static final double JACCARD_WEIGHT = 0.35;
    private static final double IMPLEMENTED_THRESHOLD = 0.44;
    private static final double PARTIAL_THRESHOLD = 0.39;
    private static final double MARGIN_IMPLEMENTED_CALIBRATED = 2.0;
    private static final double MARGIN_PARTIAL_CALIBRATED = 1.0;

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        applyCalibratedConfig(engine);
    }

    private static void applyCalibratedConfig(MatchingEngine engine) {
        ReflectionTestUtils.setField(engine, "cosineWeight", COSINE_WEIGHT);
        ReflectionTestUtils.setField(engine, "jaccardWeight", JACCARD_WEIGHT);
        ReflectionTestUtils.setField(engine, "implementedThreshold", IMPLEMENTED_THRESHOLD);
        ReflectionTestUtils.setField(engine, "partialThreshold", PARTIAL_THRESHOLD);
        ReflectionTestUtils.setField(engine, "marginImplementedThreshold", MARGIN_IMPLEMENTED_CALIBRATED);
        ReflectionTestUtils.setField(engine, "marginPartialThreshold", MARGIN_PARTIAL_CALIBRATED);
        ReflectionTestUtils.setField(engine, "topKChunks", 5);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Two-dimensional unit vector [cosine, sin] so cosineSimilarity([1,0], this) == cosine exactly. */
    private static List<Double> unitVec2(double cosine) {
        double sin = Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine));
        return List.of(cosine, sin);
    }

    private static final List<Double> REF_AXIS = List.of(1.0, 0.0);

    private static EmbeddedRequirement requirement(String reqId, List<String> keywords) {
        return new EmbeddedRequirement(reqId, "requirement text for " + reqId, keywords, REF_AXIS);
    }

    private static CodeChunkDto chunk(String filePath, double cosine, List<String> keywords) {
        CodeChunkDto c = new CodeChunkDto();
        c.setFilePath(filePath);
        c.setClassName(filePath.replace(".java", ""));
        c.setMethodName("doWork");
        c.setChunkType("method");
        c.setChunkText(filePath + ".doWork()");
        c.setLineStart(10);
        c.setKeywords(keywords);
        c.setEmbedding(unitVec2(cosine));
        return c;
    }

    private static CodeChunkDto chunkNoKeywords(String filePath, double cosine) {
        return chunk(filePath, cosine, List.of());
    }

    // ── Boundary Value Analysis: cosineSimilarity (private static, via reflection) ─

    @Nested
    class CosineSimilarityBoundaries {

        private double cosine(double[] a, double[] b) throws Exception {
            Method m = MatchingEngine.class.getDeclaredMethod("cosineSimilarity", double[].class, double[].class);
            m.setAccessible(true);
            return (double) m.invoke(null, a, b);
        }

        @Test
        void identicalVectors_returnOne() throws Exception {
            assertThat(cosine(new double[]{1, 2, 3}, new double[]{1, 2, 3})).isCloseTo(1.0, offset(1e-9));
        }

        @Test
        void orthogonalVectors_returnZero() throws Exception {
            assertThat(cosine(new double[]{1, 0}, new double[]{0, 1})).isCloseTo(0.0, offset(1e-9));
        }

        @Test
        void oppositeVectors_areClampedToZero_notNegative() throws Exception {
            // Raw cosine of opposite unit vectors is -1.0 — the method must clamp to [0,1].
            assertThat(cosine(new double[]{1, 0}, new double[]{-1, 0})).isEqualTo(0.0);
        }

        @Test
        void zeroVectorOnEitherSide_returnsZero_noDivideByZero() throws Exception {
            assertThat(cosine(new double[]{0, 0, 0}, new double[]{1, 2, 3})).isEqualTo(0.0);
            assertThat(cosine(new double[]{1, 2, 3}, new double[]{0, 0, 0})).isEqualTo(0.0);
        }

        @Test
        void mismatchedDimensions_returnsZero_defensively() throws Exception {
            assertThat(cosine(new double[]{1, 2}, new double[]{1, 2, 3})).isEqualTo(0.0);
        }

        @Test
        void emptyOrNullVectors_returnZero() throws Exception {
            assertThat(cosine(new double[]{}, new double[]{})).isEqualTo(0.0);
            assertThat(cosine(null, new double[]{1})).isEqualTo(0.0);
            assertThat(cosine(new double[]{1}, null)).isEqualTo(0.0);
        }
    }

    // ── Boundary Value Analysis: jaccardSimilarity ──────────────────────────────

    @Nested
    class JaccardSimilarityBoundaries {

        @SuppressWarnings("unchecked")
        private double jaccard(Set<String> a, Set<String> b) throws Exception {
            Method m = MatchingEngine.class.getDeclaredMethod("jaccardSimilarity", Set.class, Set.class);
            m.setAccessible(true);
            return (double) m.invoke(null, a, b);
        }

        @Test
        void identicalSets_returnOne() throws Exception {
            assertThat(jaccard(Set.of("auth", "login"), Set.of("auth", "login"))).isEqualTo(1.0);
        }

        @Test
        void disjointSets_returnZero() throws Exception {
            assertThat(jaccard(Set.of("auth"), Set.of("payment"))).isEqualTo(0.0);
        }

        @Test
        void eitherSetEmpty_returnsZero() throws Exception {
            assertThat(jaccard(Set.of(), Set.of("auth"))).isEqualTo(0.0);
            assertThat(jaccard(Set.of("auth"), Set.of())).isEqualTo(0.0);
            assertThat(jaccard(null, Set.of("auth"))).isEqualTo(0.0);
        }

        @Test
        void partialOverlap_computesExactRatio() throws Exception {
            // {a,b,c} ∩ {b,c,d} = {b,c} (2); union = {a,b,c,d} (4) → 2/4 = 0.5
            assertThat(jaccard(Set.of("a", "b", "c"), Set.of("b", "c", "d"))).isCloseTo(0.5, offset(1e-9));
        }
    }

    // ── Combined score weighting ─────────────────────────────────────────────

    @Test
    void combinedScore_appliesConfiguredWeightsExactly() {
        EmbeddedRequirement req = requirement("FR-1", List.of("a", "b"));
        CodeChunkDto c = chunk("File.java", 0.6, List.of("a", "c")); // cosine=0.6, jaccard = 1/3

        List<RequirementMatchResult> results = engine.match(List.of(req), List.of(c));

        RequirementMatchResult result = results.get(0);
        assertThat(result.getCosineScore()).isCloseTo(0.6, offset(1e-4));
        assertThat(result.getJaccardScore()).isCloseTo(1.0 / 3.0, offset(1e-4));
        // combined = 0.65*0.6 + 0.35*(1/3) = 0.5067 (rounded to 4dp, as MatchingEngine does)
        assertThat(result.getScore()).isCloseTo(0.5067, offset(1e-4));
    }

    // ── Decision table: classification (single-candidate-file, margin fallback always passes) ──

    @Nested
    class ClassificationDecisionTable {

        @Test
        void scoreAtImplementedBoundary_singleCandidate_classifiesImplemented() {
            // cosine chosen so combined == exactly 0.44 (the implemented threshold)
            double cosineForExactBoundary = IMPLEMENTED_THRESHOLD / COSINE_WEIGHT;
            CodeChunkDto c = chunkNoKeywords("Only.java", cosineForExactBoundary);

            RequirementMatchResult result = engine.match(List.of(requirement("FR-1", List.of())), List.of(c)).get(0);

            assertThat(result.getScore()).isCloseTo(IMPLEMENTED_THRESHOLD, offset(1e-4));
            assertThat(result.getStatus()).isEqualTo("IMPLEMENTED");
        }

        @Test
        void scoreJustBelowImplementedBoundary_singleCandidate_classifiesPartial() {
            double cosine = (IMPLEMENTED_THRESHOLD - 0.001) / COSINE_WEIGHT;
            CodeChunkDto c = chunkNoKeywords("Only.java", cosine);

            RequirementMatchResult result = engine.match(List.of(requirement("FR-1", List.of())), List.of(c)).get(0);

            assertThat(result.getStatus()).isEqualTo("PARTIAL");
        }

        @Test
        void scoreAtPartialBoundary_singleCandidate_classifiesPartial() {
            double cosineForExactBoundary = PARTIAL_THRESHOLD / COSINE_WEIGHT;
            CodeChunkDto c = chunkNoKeywords("Only.java", cosineForExactBoundary);

            RequirementMatchResult result = engine.match(List.of(requirement("FR-1", List.of())), List.of(c)).get(0);

            assertThat(result.getScore()).isCloseTo(PARTIAL_THRESHOLD, offset(1e-4));
            assertThat(result.getStatus()).isEqualTo("PARTIAL");
        }

        @Test
        void scoreJustBelowPartialBoundary_singleCandidate_classifiesMissing() {
            double cosine = (PARTIAL_THRESHOLD - 0.001) / COSINE_WEIGHT;
            CodeChunkDto c = chunkNoKeywords("Only.java", cosine);

            RequirementMatchResult result = engine.match(List.of(requirement("FR-1", List.of())), List.of(c)).get(0);

            assertThat(result.getStatus()).isEqualTo("MISSING");
        }

        @Test
        void zeroScore_classifiesMissing() {
            CodeChunkDto c = chunkNoKeywords("Only.java", 0.0);

            RequirementMatchResult result = engine.match(List.of(requirement("FR-1", List.of())), List.of(c)).get(0);

            assertThat(result.getScore()).isEqualTo(0.0);
            assertThat(result.getStatus()).isEqualTo("MISSING");
        }
    }

    // ── Regression: per-file grouping (documented "BUG FIX" in MatchingEngine) ──

    @Test
    void multipleChunksFromOneFile_doNotCrowdOutTheBestChunkFromAnotherFile() {
        ReflectionTestUtils.setField(engine, "topKChunks", 2);

        EmbeddedRequirement req = requirement("FR-1", List.of());
        List<CodeChunkDto> chunks = List.of(
                chunkNoKeywords("FileA.java", 0.95), // file A's 3 chunks would all outrank
                chunkNoKeywords("FileA.java", 0.90), // file B's single chunk if chunks were
                chunkNoKeywords("FileA.java", 0.30), // ranked without per-file grouping first
                chunkNoKeywords("FileB.java", 0.40)
        );

        RequirementMatchResult result = engine.match(List.of(req), chunks).get(0);
        List<ChunkMatchResult> topChunks = result.getTopChunks();

        assertThat(topChunks).hasSize(2);
        assertThat(topChunks).extracting(ChunkMatchResult::getFilePath)
                .containsExactlyInAnyOrder("FileA.java", "FileB.java");
        // File A must be represented by its single BEST chunk (cosine 0.95), not a duplicate.
        assertThat(topChunks).filteredOn(cr -> cr.getFilePath().equals("FileA.java"))
                .extracting(ChunkMatchResult::getCosineScore)
                .containsExactly(0.95);
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void noRequirements_returnsEmptyList() {
        List<RequirementMatchResult> results = engine.match(List.of(), List.of(chunkNoKeywords("A.java", 0.9)));
        assertThat(results).isEmpty();
    }

    @Test
    void noCodeChunks_allRequirementsClassifiedMissingWithZeroScore() {
        EmbeddedRequirement req = requirement("FR-1", List.of("auth"));
        List<RequirementMatchResult> results = engine.match(List.of(req), List.of());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo("MISSING");
        assertThat(results.get(0).getScore()).isEqualTo(0.0);
        assertThat(results.get(0).getTopChunks()).isEmpty();
    }

    @Test
    void getAlgorithmParams_exposesConfiguredValues() {
        var params = engine.getAlgorithmParams();

        assertThat(params.get("cosineWeight")).isEqualTo(COSINE_WEIGHT);
        assertThat(params.get("jaccardWeight")).isEqualTo(JACCARD_WEIGHT);
        assertThat(params.get("implementedThreshold")).isEqualTo(IMPLEMENTED_THRESHOLD);
        assertThat(params.get("partialThreshold")).isEqualTo(PARTIAL_THRESHOLD);
        assertThat(params.get("topKChunks")).isEqualTo(5);
    }

    // ── The margin-safety-net regression: calibrated design vs. live config ────

    /**
     * These two tests use the SAME "noisy population" fixture (five candidate
     * files whose scores all cluster tightly around the IMPLEMENTED threshold,
     * simulating shared-vocabulary noise rather than a genuine standout match)
     * and only change the margin thresholds between them.
     *
     * MatchingEngine's own Javadoc documents marginImplementedThreshold=2.0 and
     * marginPartialThreshold=1.0 as values CALIBRATED via simulation specifically
     * to reject this kind of noisy-but-above-threshold cluster. This is exactly
     * what the first test proves. The second test loads the values that are
     * actually deployed today (matching-service/src/main/resources/application.properties:
     * matching.margin.implemented=0.0, matching.margin.partial=0.0) and proves
     * that with those live values, the exact same noisy data is misclassified
     * as IMPLEMENTED — because a margin threshold of 0.0 is mathematically
     * always satisfied (the gap-based margin can never be negative).
     */
    @Nested
    class MarginSafetyNet {

        private List<CodeChunkDto> noisyCluster() {
            // Combined scores ≈ 0.450, 0.448, 0.447, 0.446, 0.445 across 5 different
            // files (cosine-only, jaccard=0) — all clear the 0.44 IMPLEMENTED score
            // threshold, but only barely separate from one another (a noisy cluster,
            // not a genuine standout). Computed standout margin ≈ 1.04.
            return List.of(
                    chunkNoKeywords("File1.java", 0.450 / COSINE_WEIGHT),
                    chunkNoKeywords("File2.java", 0.448 / COSINE_WEIGHT),
                    chunkNoKeywords("File3.java", 0.447 / COSINE_WEIGHT),
                    chunkNoKeywords("File4.java", 0.446 / COSINE_WEIGHT),
                    chunkNoKeywords("File5.java", 0.445 / COSINE_WEIGHT)
            );
        }

        @Test
        void withCalibratedMargins_noisyClusterIsDowngradedFromImplementedToPartial() {
            // engine already has calibrated margins from setUp()
            RequirementMatchResult result = engine.match(
                    List.of(requirement("FR-1", List.of())), noisyCluster()).get(0);

            assertThat(result.getScore()).isGreaterThanOrEqualTo(IMPLEMENTED_THRESHOLD);
            assertThat(result.getStandoutMargin()).isLessThan(MARGIN_IMPLEMENTED_CALIBRATED);
            assertThat(result.getStatus())
                    .as("score alone clears IMPLEMENTED, but the margin gate should catch that "
                            + "this is a noisy cluster, not a genuine standout, and downgrade it")
                    .isEqualTo("PARTIAL");
        }

        @Test
        void withLiveDeployedMargins_theSameNoisyClusterIsWronglyClassifiedImplemented() throws Exception {
            double[] liveMargins = readLiveMarginConfig();
            double liveMarginImplemented = liveMargins[0];
            double liveMarginPartial = liveMargins[1];

            ReflectionTestUtils.setField(engine, "marginImplementedThreshold", liveMarginImplemented);
            ReflectionTestUtils.setField(engine, "marginPartialThreshold", liveMarginPartial);

            RequirementMatchResult result = engine.match(
                    List.of(requirement("FR-1", List.of())), noisyCluster()).get(0);

            // Demonstrates the live config regression: with margin thresholds of 0.0,
            // the standout-margin check can never reject anything (gap >= 0 always),
            // so the same noisy data that should be PARTIAL is instead IMPLEMENTED.
            assertThat(liveMarginImplemented)
                    .as("matching.margin.implemented in application.properties is 0.0, which "
                            + "makes MatchingEngine's documented v2.4 anti-noise margin check a no-op")
                    .isEqualTo(0.0);
            assertThat(result.getStatus()).isEqualTo("IMPLEMENTED");
        }

        /** Reads matching.margin.implemented / matching.margin.partial from the real, deployed application.properties. */
        private double[] readLiveMarginConfig() throws Exception {
            Properties props = new Properties();
            try (InputStream in = MatchingEngineTest.class.getClassLoader()
                    .getResourceAsStream("application.properties")) {
                assertThat(in).as("application.properties must be on the test classpath").isNotNull();
                props.load(in);
            }
            double marginImplemented = Double.parseDouble(props.getProperty("matching.margin.implemented"));
            double marginPartial = Double.parseDouble(props.getProperty("matching.margin.partial"));
            return new double[]{marginImplemented, marginPartial};
        }
    }

    // ── Standout margin math: two-candidate-file invariant ──────────────────────

    @Test
    void withExactlyTwoDistinctFileCandidates_standoutMarginIsAlwaysSqrtTwo() {
        // With n=2 sample points, (top1-top2)/sampleStdDev is a mathematical
        // constant (sqrt(2)) regardless of the actual gap size — documenting
        // this as a known property so it isn't mistaken for a bug later, and
        // so any future change to the margin formula is caught here.
        CodeChunkDto a = chunkNoKeywords("A.java", 0.90);
        CodeChunkDto b = chunkNoKeywords("B.java", 0.50);

        RequirementMatchResult result = engine.match(
                List.of(requirement("FR-1", List.of())), List.of(a, b)).get(0);

        assertThat(result.getStandoutMargin()).isCloseTo(Math.sqrt(2), within(1e-3));
    }
}
