package com.ezzyhub.matching.controller;

import com.ezzyhub.matching.client.PythonNlpClient;
import com.ezzyhub.matching.client.RequirementAnalyzerClient;
import com.ezzyhub.matching.dto.MatchReport;
import com.ezzyhub.matching.service.MatchingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for FR-7 Requirement-to-Code Matching.
 *
 * Endpoints:
 *   POST /api/v1/match          — Run full matching pipeline
 *   GET  /api/v1/match/health   — Health check (Python + Req Analyzer)
 *   GET  /api/v1/match/info     — API metadata
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/match")
@RequiredArgsConstructor
@Tag(name = "Matching Service", description = "FR-7 Requirement-to-Code Matching Pipeline API")
public class MatchingController {

    private final MatchingService matchingService;
    private final PythonNlpClient pythonNlpClient;
    private final RequirementAnalyzerClient reqAnalyzerClient;

    /** Upper bound on the inline requirements JSON size (chars) — resource-exhaustion guard. */
    @org.springframework.beans.factory.annotation.Value("${matching.limits.max-requirements-json-chars:1000000}")
    private int maxRequirementsJsonChars;

    // =========================================================================
    // POST /api/v1/match — Main matching endpoint
    // =========================================================================

    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Match requirements against source code",
            description = """
                Upload a ZIP of Java source code and (optionally) a JSON array of requirements.

                **Pipeline:**
                1. AST-chunk the code into method/class units (Python /ast-chunk-zip)
                2. Embed each requirement with SBERT all-MiniLM-L6-v2 (Python /preprocess-and-embed)
                3. Compute Many-to-Many cosine similarity matrix (384-dim × 384-dim)
                4. Combine with Jaccard keyword similarity
                5. Classify: IMPLEMENTED / PARTIAL / MISSING
                6. Return FR-10.1 coverage report

                **Requirements source:**
                - If `requirements` JSON is provided → use those
                - If omitted → fetch all stored requirements from Requirement Analyzer DB (port 8081)

                **Requirements JSON format:**
                ```json
                [
                  {"reqId": "FR-2.1.1", "reqText": "Allow users to import source code from GitHub"},
                  {"reqText": "The system shall validate ZIP integrity"}
                ]
                ```
                `reqId` is optional — auto-generated if missing.
                """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching completed — report returned",
                    content = @Content(schema = @Schema(implementation = MatchReport.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input (not a ZIP, empty file)"),
            @ApiResponse(responseCode = "503", description = "Python NLP service unavailable"),
            @ApiResponse(responseCode = "500", description = "Internal error during matching")
    })
    public ResponseEntity<?> match(
            @Parameter(description = "ZIP file of Java source code (required)")
            @RequestParam("file") MultipartFile file,

            @Parameter(description = "JSON array of requirements [{reqId, reqText}] — optional")
            @RequestParam(value = "requirements", required = false) String requirementsJson,

            HttpServletRequest request) {

        // ── Validate file ─────────────────────────────────────────────────────
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap(400,
                    "No ZIP file uploaded or file is empty. "
                    + "Provide a 'file' field with the Java project ZIP.",
                    request.getRequestURI()));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".zip")) {
            return ResponseEntity.badRequest().body(errorMap(400,
                    "Only ZIP files are accepted. File name: " + sanitizeForMessage(filename),
                    request.getRequestURI()));
        }

        // Defense in depth: verify the content really is a ZIP (magic bytes "PK"),
        // not just an arbitrary file renamed to *.zip. Cheap 2-byte peek; rejects
        // obviously non-ZIP uploads here with a clean 400 instead of buffering the
        // whole file and shipping it downstream only to fail there.
        if (!looksLikeZip(file)) {
            return ResponseEntity.badRequest().body(errorMap(400,
                    "Uploaded file is not a valid ZIP archive (bad magic bytes).",
                    request.getRequestURI()));
        }

        // Bound the inline requirements payload cheaply, before any downstream work.
        if (requirementsJson != null && requirementsJson.length() > maxRequirementsJsonChars) {
            return ResponseEntity.badRequest().body(errorMap(400,
                    "Requirements payload too large (" + requirementsJson.length()
                    + " chars, max " + maxRequirementsJsonChars + ").",
                    request.getRequestURI()));
        }

        // Filename is user-controlled — strip CR/LF/control chars before logging to
        // prevent log forging/injection (CWE-117).
        log.info("POST /api/v1/match — file='{}' ({} bytes), requirements={}",
                sanitizeForLog(filename), file.getSize(),
                requirementsJson != null ? requirementsJson.length() + " chars" : "fetch from DB");

        // ── Run pipeline ──────────────────────────────────────────────────────
        try {
            MatchReport report = matchingService.runMatching(file, requirementsJson);
            return ResponseEntity.ok(report);

        } catch (PythonNlpClient.PythonNotReadyException e) {
            // Readiness gate tripped — Python NLP is down/warming up. Return 503 so
            // the caller knows to retry shortly, instead of a broken zero-vector report.
            log.warn("Match rejected — Python NLP not ready: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorMap(503,
                    e.getMessage(), request.getRequestURI()));

        } catch (IllegalArgumentException e) {
            // Input-validation failures (e.g. too many requirements). The message is
            // service-controlled and safe to return; this is a client error, not a 500.
            log.warn("Match rejected — invalid input: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorMap(400,
                    e.getMessage(), request.getRequestURI()));

        } catch (Exception e) {
            // Log the full detail server-side, but never echo exception text to the
            // caller: downstream errors carry internal hosts/ports and raw response
            // bodies (e.g. "Cannot reach Python /ast-chunk-zip (port 8000): ...").
            // Surfacing those is information disclosure (OWASP A05/A09). Return a
            // generic message with the correlation id instead.
            String errorId = UUID.randomUUID().toString();
            log.error("Matching pipeline error [{}]: {}", errorId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorMap(500,
                    "Matching pipeline failed due to an internal error. Reference id: " + errorId,
                    request.getRequestURI()));
        }
    }

    // =========================================================================
    // GET /api/v1/match/health — Health check
    // =========================================================================

    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Reports service status and dependent service availability")
    public ResponseEntity<Map<String, Object>> health() {
        boolean pythonHealthy = pythonNlpClient.isHealthy();
        boolean reqAnalyzerHealthy = reqAnalyzerClient.isHealthy();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "matching-service");
        body.put("version", "1.0.0");
        body.put("port", 8083);
        body.put("timestamp", Instant.now().toString());

        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("pythonNlp",            pythonHealthy ? "UP" : "DOWN");
        deps.put("requirementAnalyzer",  reqAnalyzerHealthy ? "UP" : "DOWN");
        body.put("dependencies", deps);

        Map<String, Object> algorithm = new LinkedHashMap<>();
        algorithm.put("cosineWeight",          "0.65 (configurable)");
        algorithm.put("jaccardWeight",         "0.35 (configurable)");
        algorithm.put("implementedThreshold",  "0.80 (configurable)");
        algorithm.put("partialThreshold",      "0.40 (configurable)");
        algorithm.put("chunking",              "AST-based (javalang / regex fallback)");
        algorithm.put("codeEmbedding",         "SBERT code model → 384-dim");
        algorithm.put("reqEmbedding",          "all-MiniLM-L6-v2 → 384-dim");
        body.put("algorithm", algorithm);

        return ResponseEntity.ok(body);
    }

    // =========================================================================
    // GET /api/v1/match/info — API information
    // =========================================================================

    @GetMapping("/info")
    @Operation(summary = "API information", description = "Returns API metadata and endpoint documentation")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "EzzyHub Matching Service");
        info.put("version", "1.0.0");
        info.put("description", "FR-7 Requirement-to-Code Matching with AST-based chunking and SBERT embeddings");

        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("POST /api/v1/match",         "Run full matching pipeline (multipart: file + requirements)");
        endpoints.put("GET  /api/v1/match/health",  "Health check + dependency status");
        endpoints.put("GET  /api/v1/match/info",    "This endpoint — API metadata");
        endpoints.put("GET  /swagger-ui.html",      "Swagger UI");
        endpoints.put("GET  /api-docs",             "OpenAPI JSON spec");
        info.put("endpoints", endpoints);

        Map<String, String> requestFormat = new LinkedHashMap<>();
        requestFormat.put("Content-Type", "multipart/form-data");
        requestFormat.put("file",         "ZIP file of Java source code (required)");
        requestFormat.put("requirements", "JSON [{reqId?, reqText}] — optional, fetches from DB if omitted");
        info.put("requestFormat", requestFormat);

        Map<String, String> thresholds = new LinkedHashMap<>();
        thresholds.put("IMPLEMENTED", "score ≥ 0.80 (configurable via matching.threshold.implemented)");
        thresholds.put("PARTIAL",     "0.40 ≤ score < 0.80 (configurable via matching.threshold.partial)");
        thresholds.put("MISSING",     "score < 0.40");
        info.put("classificationThresholds", thresholds);

        return ResponseEntity.ok(info);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** True if the upload begins with the ZIP local-file/archive magic bytes ("PK"). */
    private static boolean looksLikeZip(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(2);
            return head.length == 2 && head[0] == 'P' && head[1] == 'K';
        } catch (IOException e) {
            log.warn("Could not read upload header for ZIP validation: {}", e.getMessage());
            return false;
        }
    }

    /** Strip CR/LF/control characters from user-controlled values before logging (CWE-117). */
    private static String sanitizeForLog(String s) {
        if (s == null) return "null";
        String cleaned = s.replaceAll("[\\p{Cntrl}]", "_");
        return cleaned.length() > 256 ? cleaned.substring(0, 256) + "…" : cleaned;
    }

    /** Neutralize control characters in user input that is echoed back in an error message. */
    private static String sanitizeForMessage(String s) {
        if (s == null) return "null";
        String cleaned = s.replaceAll("[\\p{Cntrl}]", "_");
        return cleaned.length() > 256 ? cleaned.substring(0, 256) + "…" : cleaned;
    }

    private static Map<String, Object> errorMap(int code, String message, String path) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("status", code);
        err.put("error", code == 400 ? "Bad Request" : code == 503 ? "Service Unavailable"
                : "Internal Server Error");
        err.put("message", message);
        err.put("path", path);
        err.put("timestamp", Instant.now().toString());
        return err;
    }
}
