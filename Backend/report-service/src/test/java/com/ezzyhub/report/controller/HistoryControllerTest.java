package com.ezzyhub.report.controller;

import com.ezzyhub.report.exception.UnauthorizedException;
import com.ezzyhub.report.model.AnalysisRecord;
import com.ezzyhub.report.model.HistorySummary;
import com.ezzyhub.report.security.JwtVerifier;
import com.ezzyhub.report.service.HistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer tests for {@link HistoryController} using {@code @WebMvcTest}. The JWT
 * verifier and the service are mocked; tests cover the happy paths, the ownership
 * 404s, and the 401 returned when the token is missing/invalid.
 */
@WebMvcTest(HistoryController.class)
class HistoryControllerTest {

    @Autowired private MockMvc mvc;

    @MockBean private HistoryService historyService;
    @MockBean private JwtVerifier jwtVerifier;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String AUTH = "Bearer valid.jwt.token";

    @BeforeEach
    void setUp() {
        // Default: a valid token resolving to user "u". Individual tests override.
        when(jwtVerifier.extractUserId(any())).thenReturn("u");
    }

    private static AnalysisRecord sampleRecord(long id) {
        AnalysisRecord r = new AnalysisRecord();
        r.setId(id);
        r.setUserId("u");
        r.setProjectName("demo");
        r.setAnalysisType("CODE_ANALYSIS");
        r.setStatus("success");
        r.setCreatedAt(Instant.now());
        return r;
    }

    @Test
    void save_returns201WithId() throws Exception {
        when(historyService.save(eq("u"), any())).thenReturn(7L);

        mvc.perform(post("/api/v1/history").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("projectName", "demo", "status", "success"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("saved")));
    }

    @Test
    void list_returnsSummaries() throws Exception {
        HistorySummary s = HistorySummary.from(sampleRecord(1L));
        when(historyService.list("u")).thenReturn(List.of(s));

        mvc.perform(get("/api/v1/history").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectName").value("demo"))
                .andExpect(jsonPath("$[0].analysisType").value("CODE_ANALYSIS"));
    }

    @Test
    void list_missingOrInvalidToken_returns401() throws Exception {
        when(jwtVerifier.extractUserId(any()))
                .thenThrow(new UnauthorizedException("Missing or malformed Authorization header"));

        mvc.perform(get("/api/v1/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Authorization")));
    }

    @Test
    void getRecord_returns200WhenFound() throws Exception {
        when(historyService.getRecord(5L, "u")).thenReturn(Optional.of(sampleRecord(5L)));

        mvc.perform(get("/api/v1/history/5").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void getRecord_returns404WhenMissing() throws Exception {
        when(historyService.getRecord(9L, "u")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/history/9").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns200WhenDeleted_and404WhenNot() throws Exception {
        when(historyService.delete(5L, "u")).thenReturn(true);
        when(historyService.delete(9L, "u")).thenReturn(false);

        mvc.perform(delete("/api/v1/history/5").header("Authorization", AUTH)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/history/9").header("Authorization", AUTH)).andExpect(status().isNotFound());
    }

    @Test
    void health_returns200_withoutAuth() throws Exception {
        mvc.perform(get("/api/v1/history/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("report-service"));
    }
}
