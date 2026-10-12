package com.ezzyhub.matching.service;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import com.ezzyhub.matching.client.PythonNlpClient;
import com.ezzyhub.matching.client.RequirementAnalyzerClient;
import com.ezzyhub.matching.client.CodeAnalyzerClient;
import com.ezzyhub.matching.dto.*;
import com.ezzyhub.matching.engine.MatchingEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
/**
 * FR-7 Matching Service Orchestrator
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ STEP 1  POST /ast-chunk-zip (Python)                           │
 * │         ZIP → AST parse → method/class chunks → 384-dim embs  │
 * ├─────────────────────────────────────────────────────────────────┤
 * │ STEP 2  POST /batch-preprocess-and-embed (Python v2.2)         │
 * │         N texts → NLP pipeline → 384-dim SBERT embs           │
 * │         (Falls back to N × /preprocess-and-embed if older)     │
 * ├─────────────────────────────────────────────────────────────────┤
 * │ STEP 3  MatchingEngine (Java)                                  │
 * │         Many-to-Many:                                          │
 * │           cosine(req_emb, chunk_emb) × 0.65                   │
 * │         + jaccard(req_kws, chunk_kws) × 0.35                  │
 * │         → classify → IMPLEMENTED / PARTIAL / MISSING           │
 * ├─────────────────────────────────────────────────────────────────┤
 * │ STEP 4  Build MatchReport (FR-7.3 + FR-10.1)                  │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Requirements source (in priority order):
 *   1. JSON array in the match request body
 *   2. All stored requirements from Requirement Analyzer DB (:8081)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingService {

    private final PythonNlpClient       pythonClient;
    private final RequirementAnalyzerClient reqAnalyzerClient;
    private final CodeAnalyzerClient     codeAnalyzerClient;
    private final MatchingEngine        matchingEngine;
    private final ObjectMapper          objectMapper;

    /** When true, matching also calls Code-Analyzer (:8080) and enriches the
     *  jaccard/keyword side with each file's semantic description. Best-effort:
     *  a failure here never breaks matching. Set matching.use-code-analyzer=false to disable. */
    @org.springframework.beans.factory.annotation.Value("${matching.use-code-analyzer:true}")
    private boolean useCodeAnalyzer;

    /** Route requirements THROUGH Requirement-Analyzer (clean + embed) before matching.
     *  Falls back to embedding via Python directly if disabled or unavailable. */
    @org.springframework.beans.factory.annotation.Value("${matching.use-requirement-analyzer:true}")
    private boolean useRequirementAnalyzer;

    /** Same threshold the engine uses; reconcile reads it to avoid downgrading strong matches. */
    @org.springframework.beans.factory.annotation.Value("${matching.threshold.implemented:0.50}")
    private double implementedThreshold;

    /** Readiness gate (STEP 0): how many times to probe Python /health before aborting. */
    @org.springframework.beans.factory.annotation.Value("${matching.readiness.max-attempts:5}")
    private int readinessMaxAttempts;

    /** Readiness gate (STEP 0): seconds to wait between health probes. */
    @org.springframework.beans.factory.annotation.Value("${matching.readiness.delay-seconds:3}")
    private long readinessDelaySeconds;

    /** Upper bound on the number of requirements matched in one request. Bounds how many
     *  downstream embedding/LLM calls a single request can trigger (LLM09 / resource exhaustion). */
    @org.springframework.beans.factory.annotation.Value("${matching.limits.max-requirements:2000}")
    private int maxRequirements;

    // ── Main pipeline ─────────────────────────────────────────────────────────

    /**
     * Run the complete requirement-to-code matching pipeline.
     *
     * @param codeZip          ZIP file of Java source code (required)
     * @param requirementsJson JSON [{reqId?, reqText}] or null → fetch from DB
     * @return FR-10.1 verification report
     */
    public MatchReport runMatching(MultipartFile codeZip, String requirementsJson) {
        String reportId  = UUID.randomUUID().toString();
        Instant started  = Instant.now();
        long    t0       = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        log.info("=== EzzyHub Matching Pipeline START [{}] ===", reportId);

        // ─── STEP 0: Readiness gate (retry-then-block) ────────────────────────
        // Before doing ANY work, make sure the Python NLP service is actually
        // ready. If it's down or still loading models, every embedding call
        // would silently fall back to a zero vector and the whole report would
        // be false-MISSINGs. Retry a few times (a cold service may just need a
        // few seconds), and only abort with a clear 503 if it's still not ready.
        pythonClient.waitUntilReady(readinessMaxAttempts,
                java.time.Duration.ofSeconds(readinessDelaySeconds));
        log.info("STEP 0 ✓ Python NLP service is ready");

        // ─── STEP 1: AST-chunk and embed code ─────────────────────────────────
        log.info("STEP 1 ▶ Chunking code ZIP via Python /ast-chunk-zip ...");
        long chunkStart = System.currentTimeMillis();

        AstChunkZipResponse chunkResp = pythonClient.chunkAndEmbedCode(codeZip);
        long chunkingMs = System.currentTimeMillis() - chunkStart;

        if (chunkResp == null || chunkResp.getChunks() == null
                || chunkResp.getChunks().isEmpty()) {
            warnings.add("No Java code chunks found in the uploaded ZIP.");
            log.warn("STEP 1 ✗ No chunks returned — aborting match");
            return buildEmptyReport(reportId, started, warnings,
                    System.currentTimeMillis() - t0, chunkingMs);
        }

        List<CodeChunkDto> codeChunks = chunkResp.getChunks();
        long withEmb = codeChunks.stream()
                .filter(c -> c.getEmbedding() != null && !c.getEmbedding().isEmpty())
                .count();
        log.info("STEP 1 ✓ {} files → {} chunks ({} with embeddings) in {}ms",
                chunkResp.getTotalFiles(), codeChunks.size(), withEmb, chunkingMs);
        if (withEmb < codeChunks.size()) {
            warnings.add(String.format("%d of %d code chunks have no embedding — results may be incomplete.",
                    codeChunks.size() - withEmb, codeChunks.size()));
        }

        // STEP 1b — OPTIONAL: match against Code-Analyzer's OUTPUT, not just raw identifiers.
        // For each file we take Code-Analyzer's semantic description (what the code actually
        // does) and add it as an extra "description chunk" — embedded by Python in the SAME
        // vector space — so requirements match against described behaviour on BOTH the cosine
        // and jaccard sides. Strictly best-effort: any failure leaves codeChunks untouched.
        // Facts (capabilities + limitations) per file for the rule-based verdict in STEP 3.5.
        Map<String, com.ezzyhub.matching.client.CodeAnalyzerClient.FileFacts> fileFacts =
                java.util.Collections.emptyMap();

        if (useCodeAnalyzer) {
            try {
                fileFacts = codeAnalyzerClient.fetchFileFacts(codeZip);
                Map<String, String> descriptions = buildDescriptionsFromFacts(fileFacts);
                if (!descriptions.isEmpty()) {
                    List<CodeChunkDto> descChunks = buildDescriptionChunks(descriptions, warnings);
                    codeChunks.addAll(descChunks);
                    log.info("STEP 1b ✓ added {} Code-Analyzer description chunks (now {} total)",
                            descChunks.size(), codeChunks.size());
                } else {
                    warnings.add("Code-Analyzer returned no descriptions — matched on Python chunks only.");
                }
            } catch (Exception e) {
                log.warn("Code-Analyzer enrichment skipped: {}", e.getMessage());
                warnings.add("Code-Analyzer enrichment skipped (" + e.getMessage() + ").");
            }
        }

        // ─── STEP 2: Resolve + embed requirements ─────────────────────────────
        log.info("STEP 2 ▶ Loading and embedding requirements ...");
        long embedStart = System.currentTimeMillis();

        List<RequirementInputDto> rawReqs = parseRequirements(requirementsJson, warnings);
        // Bound fan-out: too many requirements would trigger an unbounded number of
        // downstream embedding/LLM calls (LLM09 unbounded consumption). Reject clearly.
        if (rawReqs.size() > maxRequirements) {
            throw new IllegalArgumentException("Too many requirements (" + rawReqs.size()
                    + ", max " + maxRequirements + "). Split the request into smaller batches.");
        }
        if (rawReqs.isEmpty()) {
            warnings.add("No requirements to match — provide them in the request or populate the DB.");
            return buildEmptyReport(reportId, started, warnings,
                    System.currentTimeMillis() - t0, chunkingMs);
        }

        // Prefer routing requirements THROUGH Requirement-Analyzer (:8081): it cleans,
        // structures, and embeds each requirement (via the shared Python /embed, so the
        // vectors match the code chunks). Falls back to embedding via Python directly.
        List<EmbeddedRequirement> embeddedReqs = embedViaRequirementAnalyzer(rawReqs, warnings);
        if (embeddedReqs.isEmpty()) {
            embeddedReqs = embedRequirementsBatch(rawReqs, warnings);
        }
        long embeddingMs = System.currentTimeMillis() - embedStart;
        log.info("STEP 2 ✓ {}/{} requirements embedded in {}ms",
                embeddedReqs.size(), rawReqs.size(), embeddingMs);
        if (embeddedReqs.isEmpty()) {
            warnings.add("All requirements failed to embed — check Python /batch-preprocess-and-embed logs.");
            return buildEmptyReport(reportId, started, warnings,
                    System.currentTimeMillis() - t0, chunkingMs);
        }

        // ─── STEP 3: Many-to-Many matching ────────────────────────────────────
        log.info("STEP 3 ▶ Matching {} requirements × {} chunks ...",
                embeddedReqs.size(), codeChunks.size());
        long matchStart = System.currentTimeMillis();

        List<RequirementMatchResult> results = matchingEngine.match(embeddedReqs, codeChunks);
        long matchingMs = System.currentTimeMillis() - matchStart;
        log.info("STEP 3 ✓ Matching complete in {}ms", matchingMs);

        // ─── STEP 3.5: Rule-based verdict using Code-Analyzer facts (no LLM call) ──
        // Corrects "false-friend" matches (e.g. "store passwords securely" matched to
        // plaintext password code) by reconciling the requirement's demand against the
        // matched file's capabilities + limitations from Code-Analyzer.
        if (useCodeAnalyzer && !fileFacts.isEmpty()) {
            int changed = reconcileWithFacts(results, fileFacts);
            if (changed > 0)
                log.info("STEP 3.5 ✓ rule-based verdict adjusted {} requirement(s)", changed);
        }

        // ─── STEP 4: Build report (FR-7.3, FR-10.1) ───────────────────────────
        long totalMs = System.currentTimeMillis() - t0;
        MatchReport report = buildReport(
                reportId, started, results,
                chunkResp.getTotalFiles(), codeChunks.size(),
                chunkingMs, embeddingMs, matchingMs, totalMs, warnings);

        log.info("=== EzzyHub Matching Pipeline COMPLETE: coverage={}%, {}ms [{}] ===",
                String.format("%.1f", report.getCoveragePercent()), totalMs, reportId);
        return report;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2a — Parse requirements from request body OR Requirement Analyzer DB
    // ─────────────────────────────────────────────────────────────────────────

    private List<RequirementInputDto> parseRequirements(
            String requirementsJson, List<String> warnings) {

        if (requirementsJson != null && !requirementsJson.isBlank()) {
            // The frontend may send EITHER [{reqId,reqText}] OR a plain ["text", ...] array.
            // Try the object form first, then fall back to the string form. We do NOT fall
            // back to the DB here: if the caller supplied requirements, those are what we match
            // (falling back to the DB silently matched stale stored requirements instead).
            try {
                List<RequirementInputDto> parsed = objectMapper.readValue(
                        requirementsJson.trim(), new TypeReference<>() {});
                log.info("Parsed {} requirements from request body (object form)", parsed.size());
                autoAssignIds(parsed);
                return parsed;
            } catch (Exception objErr) {
                try {
                    List<String> lines = objectMapper.readValue(
                            requirementsJson.trim(), new TypeReference<List<String>>() {});
                    List<RequirementInputDto> parsed = new ArrayList<>();
                    for (String line : lines) {
                        if (line == null || line.isBlank()) continue;
                        parsed.add(new RequirementInputDto(null, line.trim()));
                    }
                    log.info("Parsed {} requirements from request body (plain-string form)", parsed.size());
                    autoAssignIds(parsed);
                    return parsed;
                } catch (Exception strErr) {
                    log.error("Failed to parse requirementsJson as objects ({}) or strings ({})",
                            objErr.getMessage(), strErr.getMessage());
                    warnings.add("Requirements were supplied but could not be parsed: " + strErr.getMessage());
                    return Collections.emptyList();
                }
            }
        }

        log.info("No inline requirements — fetching from Requirement Analyzer DB...");
        return fetchFromDatabase(warnings);
    }

    private void autoAssignIds(List<RequirementInputDto> reqs) {
        AtomicInteger counter = new AtomicInteger(1);
        reqs.forEach(r -> {
            if (r.getReqId() == null || r.getReqId().isBlank())
                r.setReqId("REQ-AUTO-" + counter.getAndIncrement());
        });
    }

    private List<RequirementInputDto> fetchFromDatabase(List<String> warnings) {
        try {
            List<StoredRequirementDto> stored = reqAnalyzerClient.fetchAllRequirements();
            if (stored.isEmpty()) {
                warnings.add("Requirement Analyzer DB is empty.");
                return Collections.emptyList();
            }
            return stored.stream()
                    .filter(r -> r.getReqId() != null
                            && r.getCleanText() != null
                            && !r.getCleanText().isBlank())
                    .map(r -> new RequirementInputDto(r.getReqId(), r.getCleanText()))
                    .toList();
        } catch (Exception e) {
            log.error("DB fetch failed: {}", e.getMessage());
            warnings.add("Could not reach Requirement Analyzer DB: " + e.getMessage()
                    + "  →  Provide requirements in the request body.");
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2b — Embed requirements (batch preferred, serial fallback)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Embeds all requirements.
     * Tries /batch-preprocess-and-embed first (1 call, fast).
     * Falls back to N × /preprocess-and-embed if batch is unavailable.
     */
    /**
     * Sends requirements through Requirement-Analyzer (:8081), which cleans + embeds them
     * (via the shared Python /embed, so embeddings match the code chunks). Returns embedded
     * requirements ready for matching, or an empty list to signal fallback to Python.
     * Disable with matching.use-requirement-analyzer=false.
     */
    private List<EmbeddedRequirement> embedViaRequirementAnalyzer(
            List<RequirementInputDto> reqs, List<String> warnings) {
        if (!useRequirementAnalyzer) return Collections.emptyList();
        try {
            List<String> texts = reqs.stream()
                    .map(r -> (r.getReqId() != null && !r.getReqId().isBlank())
                            ? r.getReqId() + " " + r.getReqText()
                            : r.getReqText())
                    .toList();
            List<ReqAnalysisResult> analyzed = reqAnalyzerClient.analyzeBatch(texts);
            if (analyzed == null || analyzed.isEmpty()) return Collections.emptyList();

            List<EmbeddedRequirement> out = new ArrayList<>();
            int failed = 0;
            for (int i = 0; i < analyzed.size(); i++) {
                ReqAnalysisResult a = analyzed.get(i);
                if (a == null || a.getSentenceEmbedding() == null || a.getSentenceEmbedding().isEmpty()) {
                    failed++;
                    continue;
                }
                RequirementInputDto original = i < reqs.size() ? reqs.get(i) : null;
                // Never let auto-detection override an ID the caller explicitly supplied —
                // the analyzer's extracted id should only be used when we didn't have one.
                String reqId = (original != null && original.getReqId() != null && !original.getReqId().isBlank())
                        ? original.getReqId()
                        : (a.getReqId() != null && !a.getReqId().isBlank() ? a.getReqId() : "REQ-" + i);
                String reqText = (a.getReqText() != null && !a.getReqText().isBlank())
                        ? a.getReqText()
                        : (original != null ? original.getReqText() : "");
                out.add(new EmbeddedRequirement(
                        reqId,
                        reqText,
                        a.getKeywords() != null ? a.getKeywords() : Collections.emptyList(),
                        a.getSentenceEmbedding()));
            }
            if (out.isEmpty()) return Collections.emptyList();
            if (failed > 0)
                warnings.add(failed + " requirement(s) could not be processed by Requirement-Analyzer.");
            log.info("STEP 2 \u25b6 embedded {} requirements via Requirement-Analyzer", out.size());
            return out;
        } catch (Exception e) {
            log.warn("Requirement-Analyzer embedding path failed ({}) \u2014 falling back to Python",
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<EmbeddedRequirement> embedRequirementsBatch(
            List<RequirementInputDto> reqs, List<String> warnings) {

        boolean batchAvailable = pythonClient.supportsBatchEmbed();

        if (batchAvailable) {
            log.info("Python v2.2+ detected — using /batch-preprocess-and-embed");
            return embedViaBatch(reqs, warnings);
        } else {
            log.info("Python v2.1 or earlier — falling back to serial /preprocess-and-embed");
            warnings.add("Python NLP service v2.2+ not detected — using slower serial embedding. "
                    + "Update Python to get batch embedding.");
            return embedViaSerial(reqs, warnings);
        }
    }

    /** Batch: 1 HTTP call for all N requirements */
    private List<EmbeddedRequirement> embedViaBatch(
            List<RequirementInputDto> reqs, List<String> warnings) {

        // Build texts with "REQ-ID||text" prefix so Python can echo the ID back
        List<String> prefixedTexts = reqs.stream()
                .map(r -> r.getReqId() + "||" + r.getReqText())
                .toList();

        BatchPreprocessResponse batchResp;
        try {
            batchResp = pythonClient.batchPreprocessAndEmbed(prefixedTexts, "requirement");
        } catch (Exception e) {
            log.warn("Batch endpoint failed ({}), falling back to serial", e.getMessage());
            warnings.add("Batch embedding failed — falling back to serial: " + e.getMessage());
            return embedViaSerial(reqs, warnings);
        }

        if (batchResp == null || batchResp.getResults() == null) {
            log.warn("Batch response was null — falling back to serial");
            return embedViaSerial(reqs, warnings);
        }

        // Map results back to EmbeddedRequirement list
        // Use index to correlate since order is guaranteed by Python
        Map<Integer, RequirementInputDto> indexToReq = new HashMap<>();
        for (int i = 0; i < reqs.size(); i++) indexToReq.put(i, reqs.get(i));

        List<EmbeddedRequirement> result = new ArrayList<>();
        int failed = 0;
        for (BatchPreprocessResponse.SingleEmbedResult r : batchResp.getResults()) {
            if (r.getError() != null && !r.getError().isBlank()) {
                log.warn("Batch item [{}] failed: {}", r.getIndex(), r.getError());
                failed++;
                continue;
            }
            if (r.getSentenceEmbedding() == null || r.getSentenceEmbedding().isEmpty()) {
                failed++;
                continue;
            }
            // Prefer the echoed reqId from Python; fall back to our map
            RequirementInputDto original = indexToReq.get(r.getIndex());
            String reqId = (r.getReqId() != null && !r.getReqId().isBlank())
                    ? r.getReqId()
                    : (original != null ? original.getReqId() : "REQ-" + r.getIndex());
            String reqText = original != null ? original.getReqText() : "";

            result.add(new EmbeddedRequirement(
                    reqId,
                    reqText,
                    r.getKeywords() != null ? r.getKeywords() : Collections.emptyList(),
                    r.getSentenceEmbedding()
            ));
        }
        if (failed > 0)
            warnings.add(failed + " requirement(s) failed during batch embedding.");
        return result;
    }

    /** Serial fallback: N HTTP calls (one per requirement) */
    private List<EmbeddedRequirement> embedViaSerial(
            List<RequirementInputDto> reqs, List<String> warnings) {

        List<EmbeddedRequirement> result = new ArrayList<>();
        int failed = 0;
        for (RequirementInputDto req : reqs) {
            try {
                PreprocessEmbedResponse r =
                        pythonClient.preprocessAndEmbed(req.getReqText());
                if (r == null || r.getSentenceEmbedding() == null
                        || r.getSentenceEmbedding().isEmpty()) {
                    failed++;
                    continue;
                }
                result.add(new EmbeddedRequirement(
                        req.getReqId(),
                        req.getReqText(),
                        r.getKeywords() != null ? r.getKeywords() : Collections.emptyList(),
                        r.getSentenceEmbedding()
                ));
            } catch (Exception e) {
                log.warn("Serial embed failed for [{}]: {}", req.getReqId(), e.getMessage());
                failed++;
            }
        }
        if (failed > 0)
            warnings.add(failed + " requirement(s) failed during serial embedding.");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4 — Build MatchReport (FR-7.3 + FR-10.1)
    // ─────────────────────────────────────────────────────────────────────────

    private MatchReport buildReport(
            String reportId, Instant timestamp,
            List<RequirementMatchResult> matches,
            int totalJavaFiles, int totalChunks,
            long chunkingMs, long embeddingMs,
            long matchingMs, long totalMs,
            List<String> warnings) {

        long imp     = matches.stream().filter(m -> "IMPLEMENTED".equals(m.getStatus())).count();
        long partial = matches.stream().filter(m -> "PARTIAL".equals(m.getStatus())).count();
        long missing = matches.stream().filter(m -> "MISSING".equals(m.getStatus())).count();
        int  total   = matches.size();
        double coverage = total > 0
                ? Math.round((double) imp / total * 1000.0) / 10.0
                : 0.0;

        Map<String, Object> params = matchingEngine.getAlgorithmParams();

        return MatchReport.builder()
                .reportId(reportId)
                .timestamp(timestamp)
                .totalRequirements(total)
                .implementedCount((int) imp)
                .partialCount((int) partial)
                .missingCount((int) missing)
                .coveragePercent(coverage)
                .totalJavaFiles(totalJavaFiles)
                .totalCodeChunks(totalChunks)
                .cosineWeight((Double)   params.get("cosineWeight"))
                .jaccardWeight((Double)  params.get("jaccardWeight"))
                .implementedThreshold((Double) params.get("implementedThreshold"))
                .partialThreshold((Double)     params.get("partialThreshold"))
                .topKChunks((Integer)          params.get("topKChunks"))
                .processingTimeMs(totalMs)
                .chunkingTimeMs(chunkingMs)
                .embeddingTimeMs(embeddingMs)
                .matchingTimeMs(matchingMs)
                .requirementMatches(matches)
                .warnings(warnings.isEmpty() ? null : warnings)
                .build();
    }

    private MatchReport buildEmptyReport(String reportId, Instant timestamp,
                                         List<String> warnings, long totalMs, long chunkingMs) {
        return MatchReport.builder()
                .reportId(reportId).timestamp(timestamp)
                .totalRequirements(0).implementedCount(0).partialCount(0).missingCount(0)
                .coveragePercent(0.0).totalJavaFiles(0).totalCodeChunks(0)
                .processingTimeMs(totalMs).chunkingTimeMs(chunkingMs)
                .requirementMatches(Collections.emptyList())
                .warnings(warnings.isEmpty() ? null : warnings)
                .build();
    }
    /**
     * Builds one synthetic "description chunk" per file from Code-Analyzer's text,
     * embedding it via Python so it lives in the same vector space as the code chunks.
     * These let requirements match against WHAT THE CODE DOES, not just its identifiers.
     */
    /** Builds embeddable description text (role + capabilities + limitations) per file from facts. */
    private Map<String, String> buildDescriptionsFromFacts(
            Map<String, com.ezzyhub.matching.client.CodeAnalyzerClient.FileFacts> facts) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, com.ezzyhub.matching.client.CodeAnalyzerClient.FileFacts> e : facts.entrySet()) {
            com.ezzyhub.matching.client.CodeAnalyzerClient.FileFacts f = e.getValue();
            StringBuilder sb = new StringBuilder();
            if (f.semanticRole != null && !f.semanticRole.isBlank()) sb.append(f.semanticRole).append(". ");
            if (f.capabilities != null && !f.capabilities.isEmpty()) sb.append(String.join(". ", f.capabilities));
            String text = sb.toString().trim();
            if (!text.isEmpty()) out.put(e.getKey(), text);
        }
        return out;
    }

    // Quality words a requirement may demand, mapped to limitation-phrase hints that NEGATE them.
    private static final Map<String, List<String>> QUALITY_NEGATORS = Map.of(
            "secure",   List.of("plain text", "plaintext", "no hashing", "not hashed", "no encryption"),
            "hash",     List.of("plain text", "plaintext", "no hashing", "not hashed"),
            "encrypt",  List.of("plain text", "plaintext", "no encryption", "not encrypted"),
            "validat",  List.of("no validation", "no input validation", "not validated", "no email"),
            "unique",   List.of("no duplicate", "no uniqueness", "duplicate", "not unique"),
            "duplicat", List.of("no duplicate", "no uniqueness", "duplicate check")
    );
    // Action verbs that imply a write/operation; if absent in capabilities → MISSING.
    private static final List<String> ACTION_VERBS = List.of(
            "create", "add", "register", "delete", "remove", "update", "edit",
            "logout", "log out", "sign out", "upload", "export", "download");

    /**
     * Rule-based verdict using Code-Analyzer facts. For each result, looks at the matched
     * file's capabilities + limitations and downgrades false-friends:
     *  - requirement demands a quality (secure/validated/unique) the file's limitations negate → MISSING
     *  - requirement demands an action (create/delete/logout...) absent from all capabilities → MISSING
     * Returns the number of statuses changed.
     */
    private int reconcileWithFacts(
            List<RequirementMatchResult> results,
            Map<String, com.ezzyhub.matching.client.CodeAnalyzerClient.FileFacts> facts) {

        // Pool of every capability phrase across all files (for action-absence checks).
        List<String> allCaps = new ArrayList<>();
        for (var f : facts.values())
            if (f.capabilities != null) for (String c : f.capabilities) allCaps.add(c.toLowerCase());

        int changed = 0;
        for (RequirementMatchResult r : results) {
            if ("MISSING".equals(r.getStatus())) continue;       // already missing, nothing to downgrade
            String req = r.getReqText() == null ? "" : r.getReqText().toLowerCase();
            if (req.isBlank()) continue;

            // Facts for the matched file (top chunk's file), if any.
            com.ezzyhub.matching.client.CodeAnalyzerClient.FileFacts ff = null;
            if (r.getTopChunks() != null && !r.getTopChunks().isEmpty()) {
                String fp = r.getTopChunks().get(0).getFilePath();
                if (fp != null) {
                    String key = fp.contains("/") ? fp.substring(fp.lastIndexOf('/') + 1) : fp;
                    ff = facts.get(key.toLowerCase());
                }
            }

            // RULE 1 — quality demanded but negated by the matched file's limitations.
            boolean downgraded = false;
            if (ff != null && ff.limitations != null && !ff.limitations.isEmpty()) {
                String lims = String.join(" | ", ff.limitations).toLowerCase();
                for (var entry : QUALITY_NEGATORS.entrySet()) {
                    if (req.contains(entry.getKey())) {
                        for (String neg : entry.getValue()) {
                            if (lims.contains(neg)) {
                                setStatus(r, "MISSING",
                                        "Requirement demands '" + entry.getKey()
                                                + "' but code limitation indicates it is absent: " + neg);
                                changed++; downgraded = true; break;
                            }
                        }
                    }
                    if (downgraded) break;
                }
            }
            if (downgraded) continue;

            // RULE 2 — action requirement with no matching capability anywhere → MISSING.
            // Guard against over-firing: only downgrade when the base match is NOT already
            // strong (a strong score means the capability really is present), and match the
            // verb as a whole word (so "register" doesn't swallow unrelated text).
            // If most files failed analysis the capability pool is unreliable — skip Rule 2.
            boolean factsReliable = allCaps.size() >= 3;
            if (factsReliable && r.getScore() < implementedThreshold) {
                for (String verb : ACTION_VERBS) {
                    if (containsWord(req, verb)) {
                        boolean capHasVerb = allCaps.stream().anyMatch(c -> containsWord(c, verb));
                        if (!capHasVerb) {
                            setStatus(r, "MISSING",
                                    "Requirement action '" + verb + "' not found in any code capability.");
                            changed++;
                            break;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** Whole-word (or hyphen/space-bounded) containment, case-insensitive. */
    private boolean containsWord(String haystack, String word) {
        if (haystack == null || word == null) return false;
        int idx = haystack.indexOf(word);
        while (idx >= 0) {
            boolean leftOk  = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + word.length();
            boolean rightOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            idx = haystack.indexOf(word, idx + 1);
        }
        return false;
    }

    private void setStatus(RequirementMatchResult r, String status, String note) {
        // Cap at one severity level per rule — never jump two levels (IMPLEMENTED→MISSING).
        String current = r.getStatus();
        String newStatus = switch (current) {
            case "IMPLEMENTED" -> "PARTIAL";
            case "PARTIAL"     -> "MISSING";
            default            -> "MISSING";   // already at floor
        };
        r.setStatus(newStatus);

        // Reconcile the note: strip any stale "classified IMPLEMENTED/PARTIAL/MISSING"
        // sentence so the note never contradicts the new status.
        String prev = r.getConfidenceNote() == null ? "" : r.getConfidenceNote();
        String cleaned = prev.replaceAll(
                "(?i)\\s*[^.]*\\bclassified\\s+(?:IMPLEMENTED|PARTIAL|MISSING)\\b[^.]*\\.", "").trim();
        r.setConfidenceNote((cleaned.isEmpty() ? "" : cleaned + " ") + "[rule] " + note);
    }

    private List<CodeChunkDto> buildDescriptionChunks(Map<String, String> descriptions,
                                                      List<String> warnings) {
        List<CodeChunkDto> out = new ArrayList<>();
        for (Map.Entry<String, String> e : descriptions.entrySet()) {
            String fileName = e.getKey();
            String text = e.getValue();
            if (text == null || text.isBlank()) continue;
            try {
                PreprocessEmbedResponse emb = pythonClient.preprocessAndEmbed(text);
                if (emb == null || emb.getSentenceEmbedding() == null || emb.getSentenceEmbedding().isEmpty())
                    continue;
                CodeChunkDto c = new CodeChunkDto();
                c.setFilePath(fileName);
                c.setChunkType("description");
                c.setChunkText(text);
                c.setEmbedding(emb.getSentenceEmbedding());
                c.setKeywords(emb.getKeywords());
                out.add(c);
            } catch (Exception ex) {
                log.warn("Could not embed description for {}: {}", fileName, ex.getMessage());
            }
        }
        if (out.isEmpty())
            warnings.add("Code-Analyzer descriptions could not be embedded — matched on Python chunks only.");
        return out;
    }
}