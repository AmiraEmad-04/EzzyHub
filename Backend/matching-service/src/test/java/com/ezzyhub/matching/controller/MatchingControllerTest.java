package com.ezzyhub.matching.controller;

import com.ezzyhub.matching.client.PythonNlpClient;
import com.ezzyhub.matching.client.RequirementAnalyzerClient;
import com.ezzyhub.matching.dto.MatchReport;
import com.ezzyhub.matching.service.MatchingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer tests for {@link MatchingController} using {@code @WebMvcTest}.
 * The service layer is mocked; only request mapping, validation, and status
 * translation are exercised.
 */
@WebMvcTest(MatchingController.class)
class MatchingControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean private MatchingService matchingService;
    @MockBean private PythonNlpClient pythonNlpClient;
    @MockBean private RequirementAnalyzerClient reqAnalyzerClient;

    private static MockMultipartFile validZip() {
        return new MockMultipartFile("file", "code.zip", "application/zip",
                new byte[]{'P', 'K', 3, 4, 1, 2});
    }

    @Test
    void match_happyPath_returns200AndReport() throws Exception {
        MatchReport report = MatchReport.builder()
                .reportId("r-123").timestamp(Instant.now())
                .totalRequirements(1).implementedCount(1).coveragePercent(100.0)
                .build();
        when(matchingService.runMatching(any(), any())).thenReturn(report);

        mvc.perform(multipart("/api/v1/match")
                        .file(validZip())
                        .param("requirements", "[{\"reqId\":\"FR-1\",\"reqText\":\"log in\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value("r-123"))
                .andExpect(jsonPath("$.coveragePercent").value(100.0));
    }

    @Test
    void match_nonZipExtension_returns400() throws Exception {
        MockMultipartFile txt = new MockMultipartFile("file", "code.txt", "text/plain",
                "hello".getBytes());

        mvc.perform(multipart("/api/v1/match").file(txt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Only ZIP")));
    }

    @Test
    void match_zipNameButNotZipContent_returns400OnMagicBytes() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("file", "code.zip", "application/zip",
                "this is not a zip".getBytes());

        mvc.perform(multipart("/api/v1/match").file(fake))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not a valid ZIP")));
    }

    @Test
    void match_emptyFile_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "code.zip", "application/zip",
                new byte[0]);

        mvc.perform(multipart("/api/v1/match").file(empty))
                .andExpect(status().isBadRequest());
    }

    @Test
    void match_readinessGateDown_returns503() throws Exception {
        when(matchingService.runMatching(any(), any()))
                .thenThrow(new PythonNlpClient.PythonNotReadyException("Python NLP service not ready"));

        mvc.perform(multipart("/api/v1/match").file(validZip()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void match_internalError_returns500WithGenericMessage() throws Exception {
        when(matchingService.runMatching(any(), any()))
                .thenThrow(new RuntimeException("Cannot reach Python /ast-chunk-zip (port 8000): secret detail"));

        mvc.perform(multipart("/api/v1/match").file(validZip()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("internal error")))
                // the downstream detail must NOT leak to the client
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("port 8000"))));
    }

    @Test
    void health_returns200WithDependencyStatus() throws Exception {
        when(pythonNlpClient.isHealthy()).thenReturn(true);
        when(reqAnalyzerClient.isHealthy()).thenReturn(false);

        mvc.perform(get("/api/v1/match/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.dependencies.pythonNlp").value("UP"))
                .andExpect(jsonPath("$.dependencies.requirementAnalyzer").value("DOWN"));
    }
}
