package com.ezzyhub.matching.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client tests for {@link CodeAnalyzerClient} against a local {@link MockWebServer}.
 *
 * Code-Analyzer enrichment is strictly best-effort — every method must return an
 * empty result (never throw) when the downstream is down, empty, or malformed.
 */
class CodeAnalyzerClientTest {

    private MockWebServer server;
    private CodeAnalyzerClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
        client = new CodeAnalyzerClient(webClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private MultipartFile zip() {
        return new MockMultipartFile("file", "code.zip", "application/zip",
                new byte[]{'P', 'K', 3, 4});
    }

    private static final String ANALYZE_BODY = """
            {"fileAnalyses":[
              {"fileName":"UserService.java","semanticRole":"handles user accounts",
               "dependencies":["org.springframework.stereotype.Service"],
               "capabilities":["create user","delete user"],
               "limitations":["passwords stored in plain text"]}
            ]}""";

    @Test
    void fetchFileFacts_parsesRoleCapabilitiesLimitations() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(ANALYZE_BODY));

        Map<String, CodeAnalyzerClient.FileFacts> facts = client.fetchFileFacts(zip());

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/analyze");
        assertThat(req.getHeader("Content-Type")).startsWith("multipart/form-data");

        assertThat(facts).containsKey("userservice.java");
        CodeAnalyzerClient.FileFacts f = facts.get("userservice.java");
        assertThat(f.semanticRole).isEqualTo("handles user accounts");
        assertThat(f.capabilities).containsExactly("create user", "delete user");
        assertThat(f.limitations).containsExactly("passwords stored in plain text");
    }

    @Test
    void fetchFileKeywords_extractsWordsFromRoleCapabilitiesAndDeps() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(ANALYZE_BODY));

        Map<String, List<String>> kws = client.fetchFileKeywords(zip());

        assertThat(kws).containsKey("userservice.java");
        // words > 2 chars, lowercased; last segment of the dependency ("Service").
        assertThat(kws.get("userservice.java"))
                .contains("handles", "user", "accounts", "create", "delete", "service");
    }

    @Test
    void fetchFileDescriptions_joinsRoleAndCapabilities() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(ANALYZE_BODY));

        Map<String, String> desc = client.fetchFileDescriptions(zip());

        assertThat(desc).containsKey("userservice.java");
        assertThat(desc.get("userservice.java"))
                .contains("handles user accounts")
                .contains("create user");
    }

    @Test
    void fetchFileFacts_httpError_returnsEmptyMap_neverThrows() {
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThat(client.fetchFileFacts(zip())).isEmpty();
    }

    @Test
    void fetchFileFacts_emptyAnalyses_returnsEmptyMap() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"fileAnalyses\":[]}"));
        assertThat(client.fetchFileFacts(zip())).isEmpty();
    }
}
