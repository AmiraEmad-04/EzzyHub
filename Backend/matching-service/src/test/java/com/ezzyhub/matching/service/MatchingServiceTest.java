package com.ezzyhub.matching.service;

import com.ezzyhub.matching.client.CodeAnalyzerClient;
import com.ezzyhub.matching.client.PythonNlpClient;
import com.ezzyhub.matching.client.RequirementAnalyzerClient;
import com.ezzyhub.matching.dto.*;
import com.ezzyhub.matching.engine.MatchingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Orchestration tests for {@link MatchingService}: the three downstream clients are
 * mocked, and a REAL {@link MatchingEngine} (configured with the calibrated
 * production thresholds) is used so the pipeline's classification, coverage math,
 * and post-classification rule overrides are exercised end-to-end.
 *
 * Embeddings are crafted in a 6-D space (axes 0–2 carry the three requirements, axes
 * 3–5 are orthogonal "filler" so each requirement aligns with exactly one file and
 * cosine is fully deterministic). MatchingEngineTest already covers the engine's
 * boundary math in isolation; here we verify the wiring around it.
 */
class MatchingServiceTest {

    private PythonNlpClient pythonClient;
    private RequirementAnalyzerClient reqAnalyzerClient;
    private CodeAnalyzerClient codeAnalyzerClient;
    private MatchingEngine engine;
    private MatchingService service;

    @BeforeEach
    void setUp() {
        pythonClient = mock(PythonNlpClient.class);
        reqAnalyzerClient = mock(RequirementAnalyzerClient.class);
        codeAnalyzerClient = mock(CodeAnalyzerClient.class);

        engine = new MatchingEngine();
        ReflectionTestUtils.setField(engine, "cosineWeight", 0.65);
        ReflectionTestUtils.setField(engine, "jaccardWeight", 0.35);
        ReflectionTestUtils.setField(engine, "implementedThreshold", 0.44);
        ReflectionTestUtils.setField(engine, "partialThreshold", 0.39);
        ReflectionTestUtils.setField(engine, "marginImplementedThreshold", 2.0);
        ReflectionTestUtils.setField(engine, "marginPartialThreshold", 1.0);
        ReflectionTestUtils.setField(engine, "topKChunks", 5);

        service = new MatchingService(pythonClient, reqAnalyzerClient, codeAnalyzerClient,
                engine, new ObjectMapper());
        ReflectionTestUtils.setField(service, "useCodeAnalyzer", false);
        ReflectionTestUtils.setField(service, "useRequirementAnalyzer", true);
        ReflectionTestUtils.setField(service, "implementedThreshold", 0.44);
        ReflectionTestUtils.setField(service, "readinessMaxAttempts", 1);
        ReflectionTestUtils.setField(service, "readinessDelaySeconds", 0L);
        ReflectionTestUtils.setField(service, "maxRequirements", 2000);
    }

    // ── Vector helpers (length-6) ─────────────────────────────────────────────

    private static List<Double> axis(int i) {
        Double[] v = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
        v[i] = 1.0;
        return List.of(v);
    }

    /** File aligned to requirement axis {@code reqAxis} with the given cosine; the
     *  remaining magnitude sits on a filler axis so it is orthogonal to every other requirement. */
    private static List<Double> file(int reqAxis, double cosine, int fillerAxis) {
        double sin = Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine));
        Double[] v = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
        v[reqAxis] = cosine;
        v[fillerAxis] = sin;
        return List.of(v);
    }

    private static CodeChunkDto chunk(String path, List<Double> emb) {
        CodeChunkDto c = new CodeChunkDto();
        c.setFilePath(path);
        c.setClassName(path.replace(".java", ""));
        c.setMethodName("run");
        c.setChunkType("method");
        c.setChunkText(path);
        c.setEmbedding(emb);
        return c;
    }

    private static ReqAnalysisResult analyzed(String reqId, String text, List<Double> emb) {
        ReqAnalysisResult r = new ReqAnalysisResult();
        r.setReqId(reqId);
        r.setReqText(text);
        r.setKeywords(List.of());
        r.setSentenceEmbedding(emb);
        return r;
    }

    private MultipartFile zip() {
        return new MockMultipartFile("file", "code.zip", "application/zip", new byte[]{'P', 'K', 3, 4});
    }

    private AstChunkZipResponse chunkResponse(List<CodeChunkDto> chunks, int files) {
        AstChunkZipResponse r = new AstChunkZipResponse();
        r.setTotalFiles(files);
        r.setTotalChunks(chunks.size());
        r.setChunks(new ArrayList<>(chunks));
        return r;
    }

    // ── Classification thresholds + coverage math ─────────────────────────────

    @Test
    void pipeline_classifiesImplementedPartialMissing_andComputesCoverage() {
        // Five candidate files; req1↔f0 strong, req2↔f1 mid, req3↔f2 weak, f3/f4 filler.
        List<CodeChunkDto> chunks = List.of(
                chunk("UserService.java", file(0, 0.90, 3)),   // req1 → combined 0.585 → IMPLEMENTED
                chunk("EmailValidator.java", file(1, 0.62, 4)),// req2 → combined 0.403 → PARTIAL
                chunk("Report.java", file(2, 0.40, 5)),        // req3 → combined 0.260 → MISSING
                chunk("Filler1.java", axis(3)),
                chunk("Filler2.java", axis(4)));
        when(pythonClient.chunkAndEmbedCode(any())).thenReturn(chunkResponse(chunks, 5));
        when(reqAnalyzerClient.analyzeBatch(anyList())).thenReturn(List.of(
                analyzed("FR-1", "allow users to log in", axis(0)),
                analyzed("FR-2", "validate email format", axis(1)),
                analyzed("FR-3", "export a coverage report", axis(2))));

        String reqs = """
                [{"reqId":"FR-1","reqText":"allow users to log in"},
                 {"reqId":"FR-2","reqText":"validate email format"},
                 {"reqId":"FR-3","reqText":"export a coverage report"}]""";

        MatchReport report = service.runMatching(zip(), reqs);

        assertThat(report.getTotalRequirements()).isEqualTo(3);
        assertThat(report.getImplementedCount()).isEqualTo(1);
        assertThat(report.getPartialCount()).isEqualTo(1);
        assertThat(report.getMissingCount()).isEqualTo(1);
        // coverage = implemented / total × 100, rounded to 1 dp
        assertThat(report.getCoveragePercent()).isEqualTo(33.3);

        assertThat(statusOf(report, "FR-1")).isEqualTo("IMPLEMENTED");
        assertThat(statusOf(report, "FR-2")).isEqualTo("PARTIAL");
        assertThat(statusOf(report, "FR-3")).isEqualTo("MISSING");
    }

    // ── Requirement-source selection: DB fallback when no inline requirements ──

    @Test
    void pipeline_fetchesFromDatabase_whenNoInlineRequirements() {
        when(pythonClient.chunkAndEmbedCode(any())).thenReturn(chunkResponse(
                List.of(chunk("A.java", file(0, 0.9, 3))), 1));
        StoredRequirementDto stored = new StoredRequirementDto();
        stored.setReqId("DB-1");
        stored.setCleanText("stored requirement text");
        when(reqAnalyzerClient.fetchAllRequirements()).thenReturn(List.of(stored));
        when(reqAnalyzerClient.analyzeBatch(anyList()))
                .thenReturn(List.of(analyzed("DB-1", "stored requirement text", axis(0))));

        MatchReport report = service.runMatching(zip(), null);

        verify(reqAnalyzerClient).fetchAllRequirements();
        assertThat(report.getTotalRequirements()).isEqualTo(1);
        assertThat(report.getRequirementMatches().get(0).getReqId()).isEqualTo("DB-1");
    }

    // ── Empty-code short circuit ──────────────────────────────────────────────

    @Test
    void pipeline_returnsEmptyReportWithWarning_whenNoChunks() {
        when(pythonClient.chunkAndEmbedCode(any())).thenReturn(chunkResponse(List.of(), 0));

        MatchReport report = service.runMatching(zip(), "[{\"reqId\":\"FR-1\",\"reqText\":\"x\"}]");

        assertThat(report.getTotalRequirements()).isZero();
        assertThat(report.getWarnings()).anyMatch(w -> w.toLowerCase().contains("no java code chunks"));
        verify(reqAnalyzerClient, never()).analyzeBatch(anyList());
    }

    // ── Readiness gate propagation ────────────────────────────────────────────

    @Test
    void pipeline_propagatesReadinessFailure() {
        doThrow(new PythonNlpClient.PythonNotReadyException("warming up"))
                .when(pythonClient).waitUntilReady(anyInt(), any());

        assertThatThrownBy(() -> service.runMatching(zip(), "[]"))
                .isInstanceOf(PythonNlpClient.PythonNotReadyException.class);
        verify(pythonClient, never()).chunkAndEmbedCode(any());
    }

    // ── Input bound: too many requirements ────────────────────────────────────

    @Test
    void pipeline_rejectsTooManyRequirements() {
        ReflectionTestUtils.setField(service, "maxRequirements", 2);
        when(pythonClient.chunkAndEmbedCode(any())).thenReturn(chunkResponse(
                List.of(chunk("A.java", file(0, 0.9, 3))), 1));

        String reqs = "[\"a\",\"b\",\"c\"]"; // 3 > cap of 2

        assertThatThrownBy(() -> service.runMatching(zip(), reqs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many requirements");
    }

    // ── Rule override: quality negated by a limitation downgrades the match ────

    @Test
    void reconcile_downgradesFalseFriend_butNeverBelowOneLevel_regression() {
        // req demands "secure"; the matched file's limitation says passwords are plain text.
        ReflectionTestUtils.setField(service, "useCodeAnalyzer", true);

        List<CodeChunkDto> chunks = List.of(
                chunk("UserService.java", file(0, 0.90, 3)),   // strong → IMPLEMENTED before rules
                chunk("Filler1.java", axis(3)),
                chunk("Filler2.java", axis(4)),
                chunk("Filler3.java", axis(5)),
                chunk("Filler4.java", axis(1)));
        when(pythonClient.chunkAndEmbedCode(any())).thenReturn(chunkResponse(chunks, 5));
        when(reqAnalyzerClient.analyzeBatch(anyList())).thenReturn(List.of(
                analyzed("FR-9", "store passwords secure", axis(0))));

        // Facts: no role/capabilities (so no description chunks are injected), only a
        // limitation that negates the "secure" quality → Rule 1 fires.
        CodeAnalyzerClient.FileFacts facts = new CodeAnalyzerClient.FileFacts(
                "", List.of(), List.of("passwords stored in plain text"));
        when(codeAnalyzerClient.fetchFileFacts(any()))
                .thenReturn(Map.of("userservice.java", facts));

        MatchReport report = service.runMatching(zip(),
                "[{\"reqId\":\"FR-9\",\"reqText\":\"store passwords secure\"}]");

        RequirementMatchResult r = report.getRequirementMatches().get(0);

        // Score is above the implemented threshold, yet the rule must NOT drive it to MISSING;
        // setStatus caps the downgrade at one level → IMPLEMENTED becomes PARTIAL.
        assertThat(r.getScore()).isGreaterThanOrEqualTo(0.44);
        assertThat(r.getStatus()).isEqualTo("PARTIAL");
        assertThat(r.getStatus()).isNotEqualTo("MISSING");

        // The confidence note must not contradict the (now downgraded) status.
        assertThat(r.getConfidenceNote()).contains("[rule]");
        assertThat(r.getConfidenceNote()).doesNotContain("classified IMPLEMENTED");
    }

    @Test
    void reconcile_doesNotTouchResults_whenCodeAnalyzerDisabled() {
        ReflectionTestUtils.setField(service, "useCodeAnalyzer", false);
        when(pythonClient.chunkAndEmbedCode(any())).thenReturn(chunkResponse(
                List.of(chunk("UserService.java", file(0, 0.9, 3)),
                        chunk("F1.java", axis(3)), chunk("F2.java", axis(4)),
                        chunk("F3.java", axis(5)), chunk("F4.java", axis(1))), 5));
        when(reqAnalyzerClient.analyzeBatch(anyList()))
                .thenReturn(List.of(analyzed("FR-9", "store passwords secure", axis(0))));

        MatchReport report = service.runMatching(zip(),
                "[{\"reqId\":\"FR-9\",\"reqText\":\"store passwords secure\"}]");

        assertThat(report.getRequirementMatches().get(0).getStatus()).isEqualTo("IMPLEMENTED");
        verify(codeAnalyzerClient, never()).fetchFileFacts(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String statusOf(MatchReport report, String reqId) {
        return report.getRequirementMatches().stream()
                .filter(r -> reqId.equals(r.getReqId()))
                .findFirst().orElseThrow().getStatus();
    }
}
