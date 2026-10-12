package com.ezzyhub.requirementanalyzer.facade;

import com.ezzyhub.requirementanalyzer.dto.AnalysisRequest;
import com.ezzyhub.requirementanalyzer.dto.AnalysisResult;
import com.ezzyhub.requirementanalyzer.dto.ValidationResult;
import com.ezzyhub.requirementanalyzer.exception.InvalidRequirementException;
import com.ezzyhub.requirementanalyzer.nlp.AiSemanticValidator;
import com.ezzyhub.requirementanalyzer.nlp.BasicFormatValidator;
import com.ezzyhub.requirementanalyzer.nlp.RequirementIdExtractor;
import com.ezzyhub.requirementanalyzer.service.PythonNlpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequirementAnalyzerFacadeTest {

    // ── The facade's ACTUAL 4 dependencies ───────────────────────────────────
    @Mock private RequirementIdExtractor idExtractor;
    @Mock private BasicFormatValidator   basicValidator;
    @Mock private AiSemanticValidator    aiValidator;
    @Mock private PythonNlpClient        pythonNlpClient;

    @InjectMocks
    private RequirementAnalyzerFacade facade;

    // ── Shared test data ─────────────────────────────────────────────────────
    private AnalysisRequest validRequest;

    // Reusable mock results
    private PythonNlpClient.PreprocessResult preprocessOk;
    private PythonNlpClient.EmbedResult      embedOk;

    @BeforeEach
    void setUp() {
        validRequest = new AnalysisRequest();
        validRequest.setRequirementText("REQ-USER-1: The system shall allow users to register");
        validRequest.setSkipAiValidation(false);

        preprocessOk = new PythonNlpClient.PreprocessResult(
                List.of("system", "allow", "user", "register"),   // tokens
                List.of("system", "allow", "permit", "user",
                        "register", "signup"),                     // keywords (after synonym expansion)
                List.of("allow", "register"),                      // action_verbs
                List.of()                                          // warnings
        );

        embedOk = new PythonNlpClient.EmbedResult(
                new float[384],                    // sentence_embedding  (384-dim SBERT)
                Map.of("user", new float[300]),    // word_embeddings     (300-dim Word2Vec)
                List.of()                          // oov_keywords
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — Happy path: full pipeline succeeds
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Pipeline succeeds for valid requirement — returns VALID status")
    void pipelineSucceedsForValidRequirement() {
        // Arrange
        when(idExtractor.extractIdAndText(anyString()))
                .thenReturn(new String[]{"REQ-USER-1", "The system shall allow users to register"});
        when(basicValidator.validate(anyString()))
                .thenReturn(null); // null = no error
        when(aiValidator.validate(anyString()))
                .thenReturn(ValidationResult.builder()
                        .valid(true).confidence(0.95).multipleReqs(false).build());
        when(pythonNlpClient.preprocess(anyString()))
                .thenReturn(preprocessOk);
        when(pythonNlpClient.embed(anyString(), anyList()))
                .thenReturn(embedOk);

        // Act
        AnalysisResult result = facade.analyze(validRequest);

        // Assert
        assertEquals("VALID", result.getStatus());
        assertEquals("REQ-USER-1", result.getReqId());
        assertNotNull(result.getSentenceEmbedding());
        assertEquals(384, result.getSentenceEmbedding().length);
        assertNotNull(result.getTokens());
        assertFalse(result.getTokens().isEmpty());

        // Verify the 4 real dependencies were each called exactly once
        verify(idExtractor).extractIdAndText(anyString());
        verify(basicValidator).validate(anyString());
        verify(aiValidator).validate(anyString());
        verify(pythonNlpClient).preprocess(anyString());
        verify(pythonNlpClient).embed(anyString(), anyList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — Basic validation failure stops the pipeline at Step 2
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Pipeline STOPS at Step 2 when basic validation fails")
    void pipelineStopsAtStep2OnBasicValidationFailure() {
        when(idExtractor.extractIdAndText(anyString()))
                .thenReturn(new String[]{"REQ-AUTO-1", "abc"});
        when(basicValidator.validate(anyString()))
                .thenReturn("Too short");

        assertThrows(InvalidRequirementException.class, () -> facade.analyze(validRequest));

        // Steps 3-4 must NEVER be called
        verify(aiValidator,       never()).validate(anyString());
        verify(pythonNlpClient,   never()).preprocess(anyString());
        verify(pythonNlpClient,   never()).embed(anyString(), anyList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — AI validation failure stops the pipeline at Step 3
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Pipeline STOPS at Step 3 when AI validation fails")
    void pipelineStopsAtStep3OnAiValidationFailure() {
        when(idExtractor.extractIdAndText(anyString()))
                .thenReturn(new String[]{"REQ-AUTO-1", "Do something with users somehow"});
        when(basicValidator.validate(anyString()))
                .thenReturn(null);
        when(aiValidator.validate(anyString()))
                .thenReturn(ValidationResult.builder()
                        .valid(false).confidence(0.1)
                        .reason("Too vague").multipleReqs(false).build());

        assertThrows(InvalidRequirementException.class, () -> facade.analyze(validRequest));

        // Python microservice must NEVER be called
        verify(pythonNlpClient, never()).preprocess(anyString());
        verify(pythonNlpClient, never()).embed(anyString(), anyList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — skipAiValidation=true bypasses AI validator
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Pipeline skips AI validation when skipAiValidation=true")
    void skipsAiValidationWhenFlagIsSet() {
        validRequest.setSkipAiValidation(true);

        when(idExtractor.extractIdAndText(anyString()))
                .thenReturn(new String[]{"REQ-USER-1", "The system shall allow users to register"});
        when(basicValidator.validate(anyString()))
                .thenReturn(null);
        when(pythonNlpClient.preprocess(anyString()))
                .thenReturn(preprocessOk);
        when(pythonNlpClient.embed(anyString(), anyList()))
                .thenReturn(embedOk);

        AnalysisResult result = facade.analyze(validRequest);

        assertEquals("VALID", result.getStatus());
        verify(aiValidator, never()).validate(anyString()); // AI skipped!
        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.toLowerCase().contains("skipped")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — Multiple requirements warning when AI detects multipleReqs=true
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Warning added when AI detects multiple requirements in one statement")
    void addsWarningWhenMultipleRequirementsDetected() {
        when(idExtractor.extractIdAndText(anyString()))
                .thenReturn(new String[]{"REQ-1", "System shall add and delete users"});
        when(basicValidator.validate(anyString()))
                .thenReturn(null);
        when(aiValidator.validate(anyString()))
                .thenReturn(ValidationResult.builder()
                        .valid(true).confidence(0.7)
                        .multipleReqs(true).build()); // valid but multiple!
        when(pythonNlpClient.preprocess(anyString()))
                .thenReturn(preprocessOk);
        when(pythonNlpClient.embed(anyString(), anyList()))
                .thenReturn(embedOk);

        AnalysisResult result = facade.analyze(validRequest);

        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.toLowerCase().contains("split")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6 — OOV keywords from Python produce a warning in the result
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OOV keywords from Word2Vec are surfaced as a warning")
    void oovKeywordsProduceWarning() {
        PythonNlpClient.EmbedResult embedWithOov = new PythonNlpClient.EmbedResult(
                new float[384],
                Map.of("ezzyhub", new float[300]),
                List.of("ezzyhub")  // OOV keyword
        );

        when(idExtractor.extractIdAndText(anyString()))
                .thenReturn(new String[]{"REQ-1", "The system shall support EzzyHub integration"});
        when(basicValidator.validate(anyString())).thenReturn(null);
        when(aiValidator.validate(anyString()))
                .thenReturn(ValidationResult.builder()
                        .valid(true).confidence(0.9).multipleReqs(false).build());
        when(pythonNlpClient.preprocess(anyString())).thenReturn(preprocessOk);
        when(pythonNlpClient.embed(anyString(), anyList())).thenReturn(embedWithOov);

        AnalysisResult result = facade.analyze(validRequest);

        assertEquals("VALID", result.getStatus());
        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.toLowerCase().contains("oov")));
    }
}
