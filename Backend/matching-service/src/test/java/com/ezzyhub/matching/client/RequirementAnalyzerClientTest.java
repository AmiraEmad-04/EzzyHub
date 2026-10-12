package com.ezzyhub.matching.client;

import com.ezzyhub.matching.dto.ReqAnalysisResult;
import com.ezzyhub.matching.dto.StoredRequirementDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client tests for {@link RequirementAnalyzerClient} against a local {@link MockWebServer}.
 */
class RequirementAnalyzerClientTest {

    private MockWebServer server;
    private RequirementAnalyzerClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
        client = new RequirementAnalyzerClient(webClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    // ── GET /api/v1/requirements ──────────────────────────────────────────────

    @Test
    void fetchAllRequirements_parsesList() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""
                        [{"id":1,"reqId":"FR-1","cleanText":"allow login"},
                         {"id":2,"reqId":"FR-2","cleanText":"validate email"}]"""));

        List<StoredRequirementDto> result = client.fetchAllRequirements();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/api/v1/requirements");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getReqId()).isEqualTo("FR-1");
        assertThat(result.get(1).getCleanText()).isEqualTo("validate email");
    }

    @Test
    void fetchAllRequirements_httpError_throwsRuntime() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("db error"));

        assertThatThrownBy(() -> client.fetchAllRequirements())
                .isInstanceOf(RuntimeException.class);
    }

    // ── GET /api/v1/requirements/{id} ─────────────────────────────────────────

    @Test
    void fetchByReqId_notFound_returnsEmptyOptional() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertThat(client.fetchByReqId("FR-404")).isEmpty();
    }

    @Test
    void fetchByReqId_found_returnsDto() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"reqId\":\"FR-1\",\"cleanText\":\"allow login\"}"));
        assertThat(client.fetchByReqId("FR-1"))
                .isPresent()
                .get().extracting(StoredRequirementDto::getReqId).isEqualTo("FR-1");
    }

    // ── health ────────────────────────────────────────────────────────────────

    @Test
    void isHealthy_reflectsStatus() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"UP\"}"));
        assertThat(client.isHealthy()).isTrue();

        server.enqueue(new MockResponse().setResponseCode(500));
        assertThat(client.isHealthy()).isFalse();
    }

    // ── POST /api/v1/requirements/analyze/batch ───────────────────────────────

    @Test
    void analyzeBatch_sendsRequirementsAndParsesEmbeddings() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""
                        [{"reqId":"FR-1","reqText":"allow login","keywords":["login"],
                          "sentenceEmbedding":[0.1,0.2]}]"""));

        List<ReqAnalysisResult> result = client.analyzeBatch(List.of("allow login"));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/requirements/analyze/batch");
        var body = mapper.readTree(req.getBody().readUtf8());
        assertThat(body.get("requirements").get(0).asText()).isEqualTo("allow login");
        assertThat(body.get("skipAiValidation").asBoolean()).isTrue();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSentenceEmbedding()).containsExactly(0.1, 0.2);
    }

    @Test
    void analyzeBatch_error_returnsEmptyList_forGracefulFallback() {
        // analyzeBatch is best-effort: any failure returns empty so the caller
        // falls back to embedding via Python directly.
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThat(client.analyzeBatch(List.of("x"))).isEmpty();
    }
}
