package com.sonaranalyzer.controller;

import com.sonaranalyzer.service.SonarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SonarController {

    @Autowired
    private SonarService sonarService;

    /**
     * Upload ZIP file and trigger SonarQube analysis asynchronously
     * Returns jobId immediately, use /api/status/{jobId} to poll for result
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "projectName", required = false) String projectName) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded || only ZIP folders"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".zip")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only ZIP files are supported"));
        }

        // Magic bytes check: confirm the file content is actually a ZIP, not just the filename
        try {
            byte[] header = new byte[4];
            try (java.io.InputStream is = file.getInputStream()) {
                if (is.read(header) != 4 ||
                        header[0] != 0x50 || header[1] != 0x4B ||
                        (header[2] != 0x03 && header[2] != 0x05 && header[2] != 0x07)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "File is not a valid ZIP archive"));
                }
            }
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read uploaded file"));
        }

        // Whitelist validation: only letters, digits, space, dash, underscore, dot — reject anything else
        if (projectName != null && !projectName.isBlank()) {
            if (projectName.length() > 100) {
                return ResponseEntity.badRequest().body(Map.of("error", "Project name too long"));
            }
            if (!projectName.matches("^[a-zA-Z0-9 ._-]+$")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Project name contains invalid characters"));
            }
        }

        try {
            String jobId = sonarService.startAnalysisAsync(file, projectName);
            return ResponseEntity.ok(Map.of(
                    "jobId", jobId,
                    "message", "Analysis started. Poll /api/status/" + jobId + " for results."
            ));
        } catch (IllegalStateException e) {
            // Another analysis is already running
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Poll analysis status by jobId
     * Returns: { status: "RUNNING" | "DONE" | "FAILED" | "NOT_FOUND", result?, error? }
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> getStatus(@PathVariable String jobId) {
        if (!jobId.matches("^[a-zA-Z0-9-]{1,64}$")) {
            return ResponseEntity.badRequest().body(Map.of("status", "NOT_FOUND"));
        }
        Map<String, Object> job = sonarService.getJobStatus(jobId);
        if ("NOT_FOUND".equals(job.get("status"))) {
            return ResponseEntity.status(404).body(job);
        }
        return ResponseEntity.ok(job);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "sonar-analyzer"
        ));
    }
}