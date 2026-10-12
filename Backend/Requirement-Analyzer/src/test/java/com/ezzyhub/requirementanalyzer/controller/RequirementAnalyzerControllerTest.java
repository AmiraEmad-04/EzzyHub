package com.ezzyhub.requirementanalyzer.controller;

import com.ezzyhub.requirementanalyzer.dto.AnalysisRequest;
import com.ezzyhub.requirementanalyzer.dto.AnalysisResult;
import com.ezzyhub.requirementanalyzer.exception.InvalidRequirementException;
import com.ezzyhub.requirementanalyzer.service.RequirementAnalyzerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RequirementAnalyzerController.class)
class RequirementAnalyzerControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean RequirementAnalyzerService analyzerService;

    @Test
    @DisplayName("POST /analyze returns 201 with VALID result (new requirement)")
    void analyzeReturns201ForValidRequirement() throws Exception {
        AnalysisResult mockResult = AnalysisResult.builder()
                .status("VALID")
                .reqId("REQ-USER-1")
                .reqText("The system shall allow users to register")
                .tokens(List.of("system", "allow", "user", "register"))
                .keywords(List.of("system", "allow", "user", "register"))
                .sentenceEmbedding(new float[384])
                .validationConfidence(0.95)
                .build();

        // wasUpdated=false → controller returns 201
        when(analyzerService.analyzeAndSave(any()))
                .thenReturn(new RequirementAnalyzerService.SaveResult(mockResult, false));

        AnalysisRequest request = new AnalysisRequest();
        request.setRequirementText("REQ-USER-1: The system shall allow users to register");

        mockMvc.perform(post("/api/v1/requirements/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.reqId").value("REQ-USER-1"));
    }

    @Test
    @DisplayName("POST /analyze returns 200 OK when requirement is updated (duplicate reqId)")
    void analyzeReturns200ForUpdatedRequirement() throws Exception {
        AnalysisResult mockResult = AnalysisResult.builder()
                .status("VALID")
                .reqId("REQ-USER-1")
                .reqText("The system shall allow users to register")
                .tokens(List.of("allow", "user", "register"))
                .keywords(List.of("allow", "user", "register"))
                .sentenceEmbedding(new float[384])
                .validationConfidence(0.90)
                .build();

        // wasUpdated=true → controller returns 200
        when(analyzerService.analyzeAndSave(any()))
                .thenReturn(new RequirementAnalyzerService.SaveResult(mockResult, true));

        AnalysisRequest request = new AnalysisRequest();
        request.setRequirementText("REQ-USER-1: The system shall allow users to register");

        mockMvc.perform(post("/api/v1/requirements/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"));
    }

    @Test
    // FIX: GlobalExceptionHandler maps InvalidRequirementException → 422 UNPROCESSABLE_ENTITY,
    // not 400 BAD_REQUEST. The spec says HTTP 422 for semantic/format validation failures.
    @DisplayName("POST /analyze returns 422 for invalid requirement (not 400)")
    void analyzeReturns422ForInvalidRequirement() throws Exception {
        when(analyzerService.analyzeAndSave(any()))
                .thenThrow(new InvalidRequirementException("Too vague"));

        AnalysisRequest request = new AnalysisRequest();
        request.setRequirementText("abc");

        mockMvc.perform(post("/api/v1/requirements/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // FIX: was isBadRequest() (400) — corrected to isUnprocessableEntity() (422)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Too vague"));
    }

    @Test
    @DisplayName("GET /health returns UP")
    void healthReturnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /{reqId} returns 404 when not found")
    void findByReqIdReturns404WhenNotFound() throws Exception {
        when(analyzerService.findByReqId("REQ-MISSING-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/requirements/REQ-MISSING-1"))
                .andExpect(status().isNotFound());
    }
}
