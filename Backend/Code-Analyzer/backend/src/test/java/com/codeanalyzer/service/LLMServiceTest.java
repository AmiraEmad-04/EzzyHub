package com.codeanalyzer.service;

import com.codeanalyzer.config.AppConfig;
import com.codeanalyzer.model.FileSemanticAnalysis;
import com.codeanalyzer.model.ProjectStructure;
import com.codeanalyzer.model.SemanticAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LLMService} with the provider HTTP endpoint mocked by
 * {@link MockWebServer}. Uses the Anthropic provider (configurable base URL).
 * Verifies prompt assembly, JSON parsing, graceful error/timeout handling, and
 * the sensitive-file guard that keeps credential files out of the LLM prompt.
 */
class LLMServiceTest {

    private MockWebServer server;
    private AppConfig appConfig;
    private LLMService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/").toString();

        appConfig = mock(AppConfig.class);
        lenient().when(appConfig.get(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appConfig.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appConfig.get("LLM_PROVIDER", "")).thenReturn("anthropic");
        lenient().when(appConfig.get("ANTHROPIC_API_KEY")).thenReturn("test-key");
        lenient().when(appConfig.get(eq("ANTHROPIC_API_KEY"), anyString())).thenReturn("test-key");
        lenient().when(appConfig.get(eq("ANTHROPIC_BASE_URL"), anyString())).thenReturn(baseUrl);

        service = new LLMService();
        ReflectionTestUtils.setField(service, "appConfig", appConfig);
        ReflectionTestUtils.setField(service, "structureService", new ProjectStructureService());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private String anthropicResponse(String innerText) throws Exception {
        return mapper.writeValueAsString(Map.of("content", List.of(Map.of("text", innerText))));
    }

    // ── analyzeFiles: prompt assembly + JSON parsing ──────────────────────────

    @Test
    void analyzeFiles_assemblesPromptAndParsesResponse() throws Exception {
        String inner = mapper.writeValueAsString(Map.of("fileAnalyses", List.of(Map.of(
                "filePath", "src/UserService.java",
                "fileName", "UserService.java",
                "language", "Java",
                "semanticRole", "Handles user accounts",
                "capabilities", List.of("create user", "delete user"),
                "limitations", List.of("stores password in plain text (no hashing)")))));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(anthropicResponse(inner)));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/UserService.java", "class UserService { void createUser(){} }");

        List<FileSemanticAnalysis> result = service.analyzeFiles(files);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/messages");
        assertThat(req.getHeader("x-api-key")).isEqualTo("test-key");
        String sent = req.getBody().readUtf8();
        assertThat(sent).contains("=== src/UserService.java ===");
        assertThat(sent).contains("class UserService");
        assertThat(sent).contains("fileAnalyses");

        assertThat(result).hasSize(1);
        FileSemanticAnalysis fsa = result.get(0);
        assertThat(fsa.getFileName()).isEqualTo("UserService.java");
        assertThat(fsa.getSemanticRole()).isEqualTo("Handles user accounts");
        assertThat(fsa.getCapabilities()).contains("create user", "delete user");
        assertThat(fsa.getLimitations()).contains("stores password in plain text (no hashing)");
    }

    @Test
    void analyzeFiles_stripsMarkdownFences_whenModelWrapsJson() throws Exception {
        String inner = "```json\n" + mapper.writeValueAsString(Map.of("fileAnalyses", List.of(Map.of(
                "fileName", "A.java", "semanticRole", "does A")))) + "\n```";
        server.enqueue(new MockResponse().setBody(anthropicResponse(inner)));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("A.java", "class A {}");

        List<FileSemanticAnalysis> result = service.analyzeFiles(files);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSemanticRole()).isEqualTo("does A");
    }

    // ── graceful degradation ──────────────────────────────────────────────────

    @Test
    void analyzeFiles_httpError_doesNotCrash_stubsFailedFile() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream error"));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/Foo.java", "class Foo {}");

        List<FileSemanticAnalysis> result = service.analyzeFiles(files);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFilePath()).isEqualTo("src/Foo.java");
        assertThat(result.get(0).getSemanticRole()).startsWith("Analysis failed");
    }

    @Test
    void analyzeFiles_timeout_isCaught_andReturnsStub() throws Exception {
        when(appConfig.getInt(eq("LLM_TIMEOUT_SECONDS"), anyInt())).thenReturn(1);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/Slow.java", "class Slow {}");

        @SuppressWarnings("unchecked")
        List<FileSemanticAnalysis>[] holder = new List[1];
        assertThatCode(() -> holder[0] = service.analyzeFiles(files)).doesNotThrowAnyException();
        assertThat(holder[0]).hasSize(1);
        assertThat(holder[0].get(0).getSemanticRole()).startsWith("Analysis failed");
    }

    @Test
    void analyzeFiles_noSourceFiles_returnsEmpty_withoutCallingLLM() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("README.md", "# not source");

        assertThat(service.analyzeFiles(files)).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    // ── summary parsing + sensitive-file guard ────────────────────────────────

    @Test
    void summarizeProject_parsesSummaryJson() throws Exception {
        String inner = mapper.writeValueAsString(Map.of(
                "projectType", "web application",
                "mainPurpose", "manage users",
                "technicalStack", "Java, Spring Boot",
                "summary", "A backend service."));
        server.enqueue(new MockResponse().setBody(anthropicResponse(inner)));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("proj/UserService.java",
                "class UserService { /* long enough content to be used as a sample file */ void create(){} }");
        ProjectStructure structure = new ProjectStructureService().buildStructure(files, "proj");

        SemanticAnalysis s = service.summarizeProject(structure, files);

        assertThat(s.getProjectType()).isEqualTo("web application");
        assertThat(s.getTechnicalStack()).contains("Spring Boot");
    }

    @Test
    void summarizeProject_neverSendsSensitiveFileContentToLlm() throws Exception {
        server.enqueue(new MockResponse().setBody(anthropicResponse(
                mapper.writeValueAsString(Map.of("projectType", "x", "summary", "y")))));

        // The only "large" file is a .env holding a secret — it must NOT be used as the sample.
        String secret = "SECRET_API_KEY=super-secret-value-9999-that-should-never-leave-the-service";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("proj/.env", secret + "\n".repeat(10) + "MORE=padding-to-exceed-100-chars-000000000000");
        ProjectStructure structure = new ProjectStructureService().buildStructure(files, "proj");

        service.summarizeProject(structure, files);

        String sent = server.takeRequest().getBody().readUtf8();
        assertThat(sent).doesNotContain("super-secret-value-9999");
    }

    @Test
    void isConfigured_trueWhenProviderKeyPresent() {
        assertThat(service.isConfigured()).isTrue();
        assertThat(service.getProviderInfo()).startsWith("anthropic/");
    }
}
