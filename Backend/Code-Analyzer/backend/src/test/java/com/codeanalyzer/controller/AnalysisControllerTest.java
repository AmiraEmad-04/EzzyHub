package com.codeanalyzer.controller;

import com.codeanalyzer.config.AppConfig;
import com.codeanalyzer.model.AnalysisResult;
import com.codeanalyzer.service.AnalysisService;
import com.codeanalyzer.service.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link AnalysisController} using {@code @WebMvcTest}: the
 * /analyze happy path, input validation errors, the concurrency-limit 429, the
 * generic 500 (no detail leak), and health.
 */
@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private AnalysisController controller;

    @MockBean private AnalysisService analysisService;
    @MockBean private AppConfig appConfig;
    @MockBean private LLMService llmService;

    @BeforeEach
    void setUp() {
        // Config lookups return their supplied defaults unless a test overrides them.
        lenient().when(appConfig.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        // Reset the lazily-built concurrency semaphore so each test starts clean.
        ReflectionTestUtils.setField(controller, "analysisSemaphore", null);
    }

    private static MockMultipartFile zip() {
        return new MockMultipartFile("file", "project.zip", "application/zip",
                new byte[]{'P', 'K', 3, 4, 1, 2});
    }

    @Test
    void analyze_happyPath_returns200() throws Exception {
        AnalysisResult ok = new AnalysisResult();
        ok.setStatus("success");
        ok.setProjectName("project");
        when(analysisService.analyze(any())).thenReturn(ok);

        mvc.perform(multipart("/api/v1/analyze").file(zip()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.projectName").value("project"));
    }

    @Test
    void analyze_nonZipName_returns400() throws Exception {
        MockMultipartFile txt = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());
        mvc.perform(multipart("/api/v1/analyze").file(txt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Only ZIP")));
    }

    @Test
    void analyze_emptyFile_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "project.zip", "application/zip", new byte[0]);
        mvc.perform(multipart("/api/v1/analyze").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("empty")));
    }

    @Test
    void analyze_atCapacity_returns429() throws Exception {
        // Zero concurrent permits → the semaphore cannot be acquired → 429.
        when(appConfig.getInt(eq("MAX_CONCURRENT_ANALYSES"), anyInt())).thenReturn(0);

        mvc.perform(multipart("/api/v1/analyze").file(zip()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void analyze_serviceReportsError_returns500() throws Exception {
        AnalysisResult err = new AnalysisResult();
        err.setStatus("error");
        err.setError("extraction failed");
        when(analysisService.analyze(any())).thenReturn(err);

        mvc.perform(multipart("/api/v1/analyze").file(zip()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("extraction failed"));
    }

    @Test
    void analyze_serviceThrows_returns500WithGenericMessage() throws Exception {
        when(analysisService.analyze(any()))
                .thenThrow(new RuntimeException("stack trace with internal path C:\\secret"));

        mvc.perform(multipart("/api/v1/analyze").file(zip()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unexpected error")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void health_returns200WithProviderInfo() throws Exception {
        when(llmService.getProviderInfo()).thenReturn("anthropic/claude-test");
        when(llmService.isConfigured()).thenReturn(true);

        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.llmProvider").value("anthropic"))
                .andExpect(jsonPath("$.llmModel").value("claude-test"))
                .andExpect(jsonPath("$.llmConfigured").value(true));
    }
}
