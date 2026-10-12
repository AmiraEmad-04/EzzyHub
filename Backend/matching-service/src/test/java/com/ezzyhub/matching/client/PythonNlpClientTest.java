package com.ezzyhub.matching.client;

import com.ezzyhub.matching.dto.AstChunkZipResponse;
import com.ezzyhub.matching.dto.BatchPreprocessResponse;
import com.ezzyhub.matching.dto.PreprocessEmbedResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client tests for {@link PythonNlpClient} against a real local {@link MockWebServer}.
 *
 * Verifies, for each endpoint: the outbound request shape (method, path, body),
 * correct parsing of the response, and graceful behavior when the downstream
 * returns an error or an all-zero embedding vector.
 */
class PythonNlpClientTest {

    private MockWebServer server;
    private PythonNlpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        client = new PythonNlpClient(webClient, mapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private MultipartFile zip() {
        return new MockMultipartFile("file", "code.zip", "application/zip",
                new byte[]{'P', 'K', 3, 4, 1, 2, 3});
    }

    // ── /ast-chunk-zip ────────────────────────────────────────────────────────

    @Test
    void chunkAndEmbedCode_sendsMultipartAndParsesResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "total_files": 2,
                          "total_chunks": 1,
                          "chunks": [
                            {"file_path":"A.java","class_name":"A","method_name":"run",
                             "chunk_type":"method","chunk_text":"A.run()","line_start":5,
                             "keywords":["run","a"],"embedding":[0.1,0.2,0.3]}
                          ]
                        }"""));

        AstChunkZipResponse resp = client.chunkAndEmbedCode(zip());

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/ast-chunk-zip");
        assertThat(req.getHeader("Content-Type")).startsWith("multipart/form-data");

        assertThat(resp).isNotNull();
        assertThat(resp.getTotalFiles()).isEqualTo(2);
        assertThat(resp.getChunks()).hasSize(1);
        assertThat(resp.getChunks().get(0).getClassName()).isEqualTo("A");
        assertThat(resp.getChunks().get(0).getEmbedding()).containsExactly(0.1, 0.2, 0.3);
    }

    @Test
    void chunkAndEmbedCode_httpError_throwsWithStatus() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> client.chunkAndEmbedCode(zip()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ast-chunk-zip");
    }

    // ── /batch-preprocess-and-embed ───────────────────────────────────────────

    @Test
    void batchPreprocessAndEmbed_buildsJsonBodyAndParsesResults() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"total":1,"success":1,"failed":0,
                         "results":[{"index":0,"req_id":"FR-1","keywords":["login"],
                                     "sentence_embedding":[0.5,0.6]}]}"""));

        BatchPreprocessResponse resp = client.batchPreprocessAndEmbed(
                List.of("FR-1||allow \"quoted\" login\nwith newline"), "requirement");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/batch-preprocess-and-embed");
        assertThat(req.getHeader("Content-Type")).startsWith("application/json");

        // Body must be well-formed JSON with the special chars correctly escaped by Jackson.
        String body = req.getBody().readUtf8();
        var parsed = mapper.readTree(body);
        assertThat(parsed.get("mode").asText()).isEqualTo("requirement");
        assertThat(parsed.get("texts").get(0).asText())
                .isEqualTo("FR-1||allow \"quoted\" login\nwith newline");

        assertThat(resp.getSuccess()).isEqualTo(1);
        assertThat(resp.getResults().get(0).getReqId()).isEqualTo("FR-1");
        assertThat(resp.getResults().get(0).getSentenceEmbedding()).containsExactly(0.5, 0.6);
    }

    @Test
    void batchPreprocessAndEmbed_httpError_throws() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));

        assertThatThrownBy(() -> client.batchPreprocessAndEmbed(List.of("x"), "requirement"))
                .isInstanceOf(RuntimeException.class);
    }

    // ── /preprocess-and-embed ─────────────────────────────────────────────────

    @Test
    void preprocessAndEmbed_parsesEmbeddingAndKeywords() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"keywords":["validate","email"],"sentence_embedding":[0.1,0.9]}"""));

        PreprocessEmbedResponse resp = client.preprocessAndEmbed("validate email format");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/preprocess-and-embed");
        var parsed = mapper.readTree(req.getBody().readUtf8());
        assertThat(parsed.get("text").asText()).isEqualTo("validate email format");
        assertThat(parsed.get("mode").asText()).isEqualTo("requirement");

        assertThat(resp.getKeywords()).containsExactly("validate", "email");
        assertThat(resp.getSentenceEmbedding()).containsExactly(0.1, 0.9);
    }

    @Test
    void preprocessAndEmbed_allZeroVector_isParsedAndReturned_notCrashing() throws Exception {
        // The client itself does not reject zero vectors (that guard lives in the
        // Python service + readiness gate). It must, however, parse them faithfully.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"keywords":[],"sentence_embedding":[0.0,0.0,0.0,0.0]}"""));

        PreprocessEmbedResponse resp = client.preprocessAndEmbed("something");

        assertThat(resp.getSentenceEmbedding()).containsExactly(0.0, 0.0, 0.0, 0.0);
        assertThat(resp.getSentenceEmbedding().stream().allMatch(d -> d == 0.0)).isTrue();
    }

    // ── /health, readiness, version ───────────────────────────────────────────

    @Test
    void isHealthy_trueWhenStatusUp_falseOtherwise() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"UP\"}"));
        assertThat(client.isHealthy()).isTrue();

        server.enqueue(new MockResponse().setResponseCode(500));
        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void supportsBatchEmbed_parsesVersionGate() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"UP\",\"version\":\"2.2.0\"}"));
        assertThat(client.supportsBatchEmbed()).isTrue();

        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"UP\",\"version\":\"2.1.9\"}"));
        assertThat(client.supportsBatchEmbed()).isFalse();
    }

    @Test
    void waitUntilReady_throwsWhenNeverHealthy() {
        // Two failed probes → PythonNotReadyException. Use a tiny delay to keep the test fast.
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client.waitUntilReady(2, Duration.ofMillis(1)))
                .isInstanceOf(PythonNlpClient.PythonNotReadyException.class);
    }

    @Test
    void waitUntilReady_returnsWhenHealthy() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"UP\"}"));
        // Should not throw.
        client.waitUntilReady(3, Duration.ofMillis(1));
    }
}
