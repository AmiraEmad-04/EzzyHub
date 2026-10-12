package com.ezzyhub.requirementanalyzer.matching;

import com.ezzyhub.requirementanalyzer.model.Requirement;
import com.ezzyhub.requirementanalyzer.model.VerificationReport;
import com.ezzyhub.requirementanalyzer.repository.RequirementRepository;
import com.ezzyhub.requirementanalyzer.repository.VerificationReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the full Req ↔ Code matching pipeline (FR-7):
 *
 *   1. Load requirements from DB (pre-analyzed, embeddings cached)
 *   2. Send ZIP to Code Analyzer → semantic analysis
 *   3. Extract CodeFeatures + Python NLP embeddings (parallel)
 *   4. Match each requirement against all features (Stage 1: bi-encoder)
 *   5. Cross-encoder re-rank top-K per requirement (Stage 2: HF)
 *   6. Classify, compute coverage, build MatchReport
 *   7. Persist MatchReport to DB so it can be retrieved later (NEW)
 *
 * Optionally filter by a set of requirementIds so users can re-match
 * only a subset without re-processing the whole codebase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchOrchestrator {

    private final CodeAnalyzerClient          codeAnalyzerClient;
    private final CodeFeatureExtractorService featureExtractor;
    private final MatchingService             matchingService;
    private final RequirementRepository       requirementRepository;
    private final VerificationReportRepository reportRepository;
    private final ObjectMapper                objectMapper;

    public MatchReport match(MultipartFile zipFile, Set<String> filterIds) {
        long start = System.currentTimeMillis();
        log.info("Starting matching pipeline — file={} size={} MB",
                zipFile.getOriginalFilename(),
                String.format("%.1f", zipFile.getSize() / 1024.0 / 1024.0));

        // Step 1: Load requirements from DB
        List<Requirement> requirements = loadRequirements(filterIds);
        if (requirements.isEmpty()) {
            log.warn("No requirements found in DB — analyze requirements first via /api/v1/requirements/analyze");
            return emptyReport(zipFile.getOriginalFilename());
        }
        log.info("Loaded {} requirements from DB", requirements.size());

        // Step 2: Code analysis
        CodeAnalyzerClient.AnalysisResult analysis;
        try {
            analysis = codeAnalyzerClient.analyze(zipFile);
        } catch (Exception e) {
            log.error("Code Analyzer failed: {}", e.getMessage());
            throw new RuntimeException("Code analysis failed: " + e.getMessage(), e);
        }
        log.info("Code Analyzer returned {} file analyses",
                analysis.getFileAnalyses() != null ? analysis.getFileAnalyses().size() : 0);

        // Step 3: Extract CodeFeatures with NLP embeddings (parallel)
        List<CodeFeature> features = featureExtractor.extract(analysis);
        log.info("Extracted {} code features", features.size());

        if (features.isEmpty()) {
            log.warn("No code features extracted — check that Python NLP service is running and code has semantic text");
        }

        // Steps 4 & 5: Match each requirement (bi-encoder + cross-encoder rerank)
        List<MatchResult> results = requirements.stream()
                .map(req -> matchingService.match(req, features))
                .collect(Collectors.toList());

        // Step 6: Compute coverage
        long implementedCount = results.stream().filter(r -> r.getStatus() == MatchStatus.IMPLEMENTED).count();
        long partialCount     = results.stream().filter(r -> r.getStatus() == MatchStatus.PARTIAL).count();
        long missingCount     = results.stream().filter(r -> r.getStatus() == MatchStatus.MISSING).count();
        double coverage       = requirements.isEmpty() ? 0.0
                : (double) implementedCount / requirements.size() * 100.0;

        long elapsed = System.currentTimeMillis() - start;
        log.info("Matching done in {}ms — IMPL={} PART={} MISS={} COV={}%",
                elapsed, implementedCount, partialCount, missingCount,
                String.format("%.1f", coverage));

        MatchReport report = MatchReport.builder()
                .projectName(zipFile.getOriginalFilename())
                .generatedAt(Instant.now())
                .results(results)
                .totalRequirements(requirements.size())
                .implementedCount((int) implementedCount)
                .partialCount((int) partialCount)
                .missingCount((int) missingCount)
                .coveragePercentage(Math.round(coverage * 10.0) / 10.0)
                .processingTimeMs(elapsed)
                .rerankUsed(true)
                .codeAnalyzerVersion(analysis.getLlmProvider())
                .build();

        // Step 7: Persist report to DB (NEW — was previously lost after each request)
        persistReport(report);

        return report;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Requirement> loadRequirements(Set<String> filterIds) {
        if (filterIds != null && !filterIds.isEmpty()) {
            return filterIds.stream()
                    .map(id -> requirementRepository.findByReqId(id.trim()))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .collect(Collectors.toList());
        }
        return requirementRepository.findAll();
    }

    private void persistReport(MatchReport report) {
        try {
            String json = objectMapper.writeValueAsString(report);
            VerificationReport entity = VerificationReport.builder()
                    .projectName(report.getProjectName())
                    .generatedAt(report.getGeneratedAt())
                    .totalRequirements(report.getTotalRequirements())
                    .implementedCount(report.getImplementedCount())
                    .partialCount(report.getPartialCount())
                    .missingCount(report.getMissingCount())
                    .coveragePercentage(report.getCoveragePercentage())
                    .processingTimeMs(report.getProcessingTimeMs())
                    .reportJson(json)
                    .build();
            reportRepository.save(entity);
            log.info("MatchReport persisted to DB");
        } catch (Exception e) {
            // Non-fatal: report is still returned to the caller even if persistence fails
            log.error("Failed to persist MatchReport to DB: {}", e.getMessage());
        }
    }

    private MatchReport emptyReport(String projectName) {
        return MatchReport.builder()
                .projectName(projectName)
                .generatedAt(Instant.now())
                .results(List.of())
                .totalRequirements(0)
                .implementedCount(0).partialCount(0).missingCount(0)
                .coveragePercentage(0.0)
                .processingTimeMs(0)
                .build();
    }
}
