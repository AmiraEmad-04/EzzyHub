package com.codeanalyzer.controller;

import com.codeanalyzer.config.AppConfig;
import com.codeanalyzer.model.AnalysisResult;
import com.codeanalyzer.model.ApiError;
import com.codeanalyzer.service.AnalysisService;
import com.codeanalyzer.service.LLMService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Code Analyzer", description = "AI-powered code structure and semantic analysis API")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    @Autowired private AnalysisService analysisService;
    @Autowired private AppConfig appConfig;
    @Autowired private LLMService llmService;

    // Bounds how many /analyze requests (each parsing a large ZIP and making LLM calls)
    // run at once, to avoid unbounded memory/CPU/LLM-cost consumption under load.
    private volatile Semaphore analysisSemaphore;

    private Semaphore analysisSemaphore() {
        Semaphore s = analysisSemaphore;
        if (s == null) {
            synchronized (this) {
                s = analysisSemaphore;
                if (s == null) {
                    s = new Semaphore(appConfig.getInt("MAX_CONCURRENT_ANALYSES", 10));
                    analysisSemaphore = s;
                }
            }
        }
        return s;
    }

    // =========================================================
    // POST /api/v1/analyze
    // =========================================================

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Analyze a ZIP file",
            description = "Upload a ZIP file containing source code. Returns structural analysis and AI-powered semantic analysis."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analysis completed successfully",
                    content = @Content(schema = @Schema(implementation = AnalysisResult.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input (not a ZIP, too large, empty)"),
            @ApiResponse(responseCode = "429", description = "Too many concurrent analysis requests"),
            @ApiResponse(responseCode = "500", description = "Internal server error during analysis")
    })
    public ResponseEntity<?> analyze(
            @Parameter(description = "ZIP file containing the source code to analyze", required = true)
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {

        // Validate input
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    new ApiError(400, "Bad Request", "No file provided or file is empty", request.getRequestURI()));
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.toLowerCase().endsWith(".zip") &&
                !fileName.toLowerCase().endsWith(".tar.gz") &&
                !fileName.toLowerCase().endsWith(".tgz"))) {
            return ResponseEntity.badRequest().body(
                    new ApiError(400, "Bad Request",
                            "Only ZIP files are supported (.zip, .tar.gz, .tgz)", request.getRequestURI()));
        }

        log.info("Received analysis request: {} ({} bytes)", fileName, file.getSize());

        Semaphore semaphore = analysisSemaphore();
        if (!semaphore.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                    new ApiError(429, "Too Many Requests",
                            "Server is at capacity for analysis requests, please retry shortly", request.getRequestURI()));
        }

        try {
            AnalysisResult result = analysisService.analyze(file);

            if ("error".equals(result.getStatus())) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                        new ApiError(500, "Analysis Failed", result.getError(), request.getRequestURI()));
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Unexpected error during analysis", e);
            // Full detail is logged above; only a generic message goes to the client
            // to avoid leaking internal exception/stack-trace details.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ApiError(500, "Internal Server Error", "An unexpected error occurred while processing the request.", request.getRequestURI()));
        } finally {
            semaphore.release();
        }
    }

    // =========================================================
    // GET /api/v1/health
    // =========================================================

    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Returns service health and configuration status")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "code-analyzer");
        health.put("version", "1.0.0");
        health.put("timestamp", Instant.now().toString());

        // Config status (without exposing keys)
        String[] providerInfo = llmService.getProviderInfo().split("/", 2);
        health.put("llmProvider", providerInfo[0]);
        health.put("llmModel", providerInfo.length > 1 ? providerInfo[1] : "unknown");
        health.put("llmConfigured", llmService.isConfigured());

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxZipSizeMB",    appConfig.getLong("MAX_ZIP_SIZE_MB",   500));
        limits.put("maxFilesForLLM",  appConfig.getInt("MAX_FILES_FOR_LLM",  60));
        limits.put("maxDeepFiles",    appConfig.getInt("MAX_DEEP_FILES",      25));
        limits.put("maxCharsPerFile", appConfig.getInt("MAX_CHARS_PER_FILE", 4000));
        health.put("limits", limits);

        return ResponseEntity.ok(health);
    }

    // =========================================================
    // GET /api/v1/info
    // =========================================================

    @GetMapping("/info")
    @Operation(summary = "API information", description = "Returns API metadata and available endpoints")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "Code Analyzer API");
        info.put("version", "1.0.0");
        info.put("description", "AI-powered source code structure and semantic analysis microservice");

        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("POST /api/v1/analyze", "Upload and analyze a ZIP file");
        endpoints.put("GET /api/v1/health", "Service health check");
        endpoints.put("GET /api/v1/info", "API information");
        endpoints.put("GET /swagger-ui.html", "Swagger UI documentation");
        endpoints.put("GET /api-docs", "OpenAPI JSON spec");
        info.put("endpoints", endpoints);

        Map<String, Object> supported = new LinkedHashMap<>();
        supported.put("fileFormats", new String[]{"zip", "tar.gz", "tgz"});
        supported.put("llmProviders", new String[]{"anthropic", "openai", "gemini"});
        info.put("supported", supported);

        return ResponseEntity.ok(info);
    }

    // =========================================================
    // Global exception handler
    // =========================================================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGlobalException(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), e.getMessage(), e);
        // Full detail is logged above; only a generic message goes to the client
        // to avoid leaking internal exception/stack-trace details.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiError(500, "Internal Server Error", "An unexpected error occurred.", request.getRequestURI()));
    }
}