package com.ezzyhub.requirementanalyzer.matching;

import com.ezzyhub.requirementanalyzer.model.VerificationReport;
import com.ezzyhub.requirementanalyzer.repository.VerificationReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * REST controller for the Req ↔ Code matching pipeline.
 *
 * POST /api/v1/match
 *   Accepts a ZIP file (the project to analyze).
 *   Optional requirementIds param to filter which requirements to match.
 *   Runs the full 3-stage pipeline and persists the result.
 *
 * GET  /api/v1/match/reports
 *   Lists past reports (id, project, date, coverage — no full JSON blob).
 *
 * GET  /api/v1/match/reports/{id}
 *   Returns the full MatchReport JSON for a specific past run.
 *
 * DELETE /api/v1/match/reports/{id}
 *   Deletes a stored report.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/match")
@RequiredArgsConstructor
public class MatchController {

    private final MatchOrchestrator             orchestrator;
    private final VerificationReportRepository  reportRepository;
    private final ObjectMapper                  objectMapper;

    // ── Run matching ──────────────────────────────────────────────────────────

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<MatchReport> match(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "requirementIds", required = false) Set<String> requirementIds) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("POST /api/v1/match — file={} ids={}", file.getOriginalFilename(), requirementIds);
        MatchReport report = orchestrator.match(file, requirementIds);
        return ResponseEntity.ok(report);
    }

    // ── Report history ────────────────────────────────────────────────────────

    @GetMapping("/reports")
    public ResponseEntity<List<ReportSummary>> listReports() {
        List<ReportSummary> summaries = reportRepository.findTop20ByOrderByGeneratedAtDesc()
                .stream()
                .map(r -> new ReportSummary(
                        r.getId(), r.getProjectName(), r.getGeneratedAt().toString(),
                        r.getTotalRequirements(), r.getImplementedCount(),
                        r.getPartialCount(), r.getMissingCount(),
                        r.getCoveragePercentage(), r.getProcessingTimeMs()))
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<?> getReport(@PathVariable Long id) {
        return reportRepository.findById(id)
                .map(r -> {
                    try {
                        // Return the persisted MatchReport JSON directly
                        Object parsed = objectMapper.readValue(r.getReportJson(), Object.class);
                        return ResponseEntity.ok(parsed);
                    } catch (Exception e) {
                        log.error("Failed to parse stored report {}: {}", id, e.getMessage());
                        return ResponseEntity.internalServerError()
                                .body("Failed to parse stored report: " + e.getMessage());
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/reports/{id}")
    public ResponseEntity<Void> deleteReport(@PathVariable Long id) {
        if (!reportRepository.existsById(id)) return ResponseEntity.notFound().build();
        reportRepository.deleteById(id);
        log.info("Deleted report {}", id);
        return ResponseEntity.noContent().build();
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        long count = reportRepository.count();
        return ResponseEntity.ok(java.util.Map.of(
                "status",        "UP",
                "storedReports", count
        ));
    }

    // ── Summary DTO ───────────────────────────────────────────────────────────

    public record ReportSummary(
            Long   id,
            String projectName,
            String generatedAt,
            int    totalRequirements,
            int    implementedCount,
            int    partialCount,
            int    missingCount,
            double coveragePercentage,
            long   processingTimeMs) {}
}
