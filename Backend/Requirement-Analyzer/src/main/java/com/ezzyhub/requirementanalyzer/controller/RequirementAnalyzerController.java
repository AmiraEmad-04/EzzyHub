package com.ezzyhub.requirementanalyzer.controller;

import com.ezzyhub.requirementanalyzer.dto.AnalysisRequest;
import com.ezzyhub.requirementanalyzer.dto.AnalysisResult;
import com.ezzyhub.requirementanalyzer.dto.BatchAnalysisRequest;
import com.ezzyhub.requirementanalyzer.dto.RequirementSummaryDto;
import com.ezzyhub.requirementanalyzer.model.Requirement;
import com.ezzyhub.requirementanalyzer.service.RequirementAnalyzerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for the Requirement Analysis Module.
 *
 * FIXES applied:
 *  Issue #2  — GET /requirements now returns RequirementSummaryDto (no LocalDateTime
 *               serialization failure, no embedding blobs).
 *  Issue #8  — Batch endpoint now accepts BatchAnalysisRequest with explicit
 *               skipAiValidation flag — never silently skips.
 *  Issue #9  — POST /analyze returns 201 Created for new, 200 OK for update.
 *  Issue #12 — Duplicate handling returns 200 OK (not 201) and sets wasUpdated flag.
 *  Issue #14 — GET /requirements returns summary DTOs (no megabyte embedding blobs).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/requirements")
@RequiredArgsConstructor
@Tag(name = "Requirement Analyzer", description = "EzzyHub Requirement Analysis Pipeline API")
public class RequirementAnalyzerController {

    private final RequirementAnalyzerService analyzerService;

    // ─── POST /analyze — Full pipeline ──────────────────────────────────────

    @PostMapping("/analyze")
    @Operation(
            summary = "Analyze a software requirement",
            description = """
                    Runs the full NLP pipeline (13 steps across 4 phases).
                    Returns 201 Created for a new requirement, 200 OK if updated.
                    """
    )
    public ResponseEntity<AnalysisResult> analyze(
            @Valid @RequestBody AnalysisRequest request) {

        log.info("POST /analyze - input: '{}'", request.getRequirementText());
        RequirementAnalyzerService.SaveResult saveResult = analyzerService.analyzeAndSave(request);

        // FIX Issue #9/#12: 201 for new insert, 200 for update
        HttpStatus status = saveResult.wasUpdated() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(saveResult.result());
    }

    // ─── POST /analyze/batch — Batch processing ──────────────────────────────

    @PostMapping("/analyze/batch")
    @Operation(
            summary = "Batch analyze multiple requirements",
            description = """
                    Runs the pipeline on each requirement. Invalid ones return partial errors.
                    
                    FIX Issue #8: skipAiValidation is now an explicit field in the request body.
                    The caller decides whether to skip AI — it is never silently forced.
                    Default is false (AI validation runs for every item unless you set it to true).
                    """
    )
    public ResponseEntity<List<AnalysisResult>> analyzeBatch(
            @Valid @RequestBody BatchAnalysisRequest batchRequest) {

        log.info("POST /analyze/batch - {} requirements, skipAiValidation={}",
                batchRequest.getRequirements().size(), batchRequest.isSkipAiValidation());

        List<AnalysisResult> results = batchRequest.getRequirements().stream()
                .map(text -> {
                    try {
                        AnalysisRequest req = new AnalysisRequest();
                        req.setRequirementText(text);
                        // FIX: use the caller-supplied flag, not a hard-coded true
                        req.setSkipAiValidation(batchRequest.isSkipAiValidation());
                        return analyzerService.analyzeAndSave(req).result();
                    } catch (Exception e) {
                        return AnalysisResult.builder()
                                .status("INVALID")
                                .originalInput(text)
                                .errorMessage(e.getMessage())
                                .build();
                    }
                })
                .toList();

        return ResponseEntity.ok(results);
    }

    // ─── GET / — List all (summary, no embedding blobs) ─────────────────────

    @GetMapping
    @Operation(
            summary = "List all analyzed requirements",
            description = "Returns a summary DTO for each requirement (no embedding blobs). " +
                          "FIX Issue #2 & #14: uses RequirementSummaryDto — no LocalDateTime " +
                          "serialization errors, no megabytes of float arrays."
    )
    public ResponseEntity<List<RequirementSummaryDto>> findAll() {
        List<RequirementSummaryDto> summaries = analyzerService.findAll().stream()
                .map(RequirementSummaryDto::from)
                .toList();
        return ResponseEntity.ok(summaries);
    }

    // ─── GET /{reqId} — Get by requirement ID ────────────────────────────────

    @GetMapping("/{reqId}")
    @Operation(summary = "Get a requirement by its ID (e.g. REQ-USER-1)")
    public ResponseEntity<RequirementSummaryDto> findByReqId(
            @Parameter(description = "Requirement ID") @PathVariable String reqId) {

        return analyzerService.findByReqId(reqId)
                .map(req -> ResponseEntity.ok(RequirementSummaryDto.from(req)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── DELETE /{id} — Delete by DB PK ─────────────────────────────────────

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a requirement by database ID")
    public ResponseEntity<Map<String, String>> deleteById(@PathVariable Long id) {
        analyzerService.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Requirement deleted", "id", id.toString()));
    }

    // ─── GET /health ─────────────────────────────────────────────────────────

    @GetMapping("/health")
    @Operation(summary = "Health check endpoint")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "module", "Requirement Analyzer",
                "version", "2.0.0"
        ));
    }
}
