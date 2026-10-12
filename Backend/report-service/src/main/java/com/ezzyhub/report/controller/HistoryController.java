package com.ezzyhub.report.controller;

import com.ezzyhub.report.exception.UnauthorizedException;
import com.ezzyhub.report.model.AnalysisRecord;
import com.ezzyhub.report.model.HistorySummary;
import com.ezzyhub.report.model.SaveRequest;
import com.ezzyhub.report.security.JwtVerifier;
import com.ezzyhub.report.service.HistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * All history and report endpoints live under /api/v1/history.
 *
 * User identity: verified from the Authorization: Bearer <token> header —
 * the same JWT auth-service issues on login/register. This USED TO trust a
 * plain client-supplied X-User-Id header instead, which anyone could edit
 * in devtools to read or delete another user's reports. Now the id comes
 * out of a signature-checked token instead, via JwtVerifier.
 */
@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    @Autowired private HistoryService historyService;
    @Autowired private JwtVerifier jwtVerifier;

    // PDF rendering (iText) is the most CPU/memory-intensive operation this service
    // performs; bound how many run at once so a burst of requests can't pile up
    // unbounded work. Requests beyond this get an immediate 429 instead of queuing.
    private final Semaphore pdfSemaphore = new Semaphore(5);

    // ── POST /api/v1/history  →  save a new analysis ─────────────────────

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> save(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody SaveRequest req) {
        String userId = jwtVerifier.extractUserId(authorization);
        try {
            Long id = historyService.save(userId, req);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("message", "Analysis saved successfully");
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (Exception e) {
            // Full detail is logged here; only a generic message goes to the client
            // to avoid leaking internal (e.g. DB) exception details.
            log.error("Error saving analysis for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to save analysis"));
        }
    }

    // ── GET /api/v1/history  →  list all records for the user ────────────

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<HistorySummary>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = jwtVerifier.extractUserId(authorization);
        List<HistorySummary> summaries = historyService.list(userId);
        return ResponseEntity.ok(summaries);
    }

    // ── GET /api/v1/history/{id}  →  full record JSON ────────────────────

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getRecord(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        String userId = jwtVerifier.extractUserId(authorization);
        Optional<AnalysisRecord> opt = historyService.getRecord(id, userId);
        return opt.<ResponseEntity<?>>map(ResponseEntity::ok)
                  .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                          .body(Map.of("error", "Record not found")));
    }

    // ── DELETE /api/v1/history/{id}  →  delete a record ──────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        String userId = jwtVerifier.extractUserId(authorization);
        boolean deleted = historyService.delete(id, userId);
        if (deleted)
            return ResponseEntity.ok(Map.of("message", "Record deleted", "id", id));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Record not found or access denied"));
    }

    // ── GET /api/v1/history/{id}/pdf  →  download PDF report ─────────────

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        String userId = jwtVerifier.extractUserId(authorization);

        if (!pdfSemaphore.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        try {
            byte[] pdf = historyService.generatePdf(id, userId);
            String filename = "ezzyhub-report-" + id + "-"
                    + DateTimeFormatter.ofPattern("yyyyMMdd")
                              .withZone(ZoneId.of("UTC"))
                              .format(Instant.now())
                    + ".pdf";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(
                    ContentDisposition.attachment().filename(filename).build());
            headers.setContentLength(pdf.length);
            return ResponseEntity.ok().headers(headers).body(pdf);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("PDF generation failed for record {} user {}: {}", id, userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            pdfSemaphore.release();
        }
    }

    // ── GET /api/v1/history/health ────────────────────────────────────────
    // Intentionally not behind auth — same as every other service's health check.

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "report-service");
        body.put("version", "1.0.0");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    // ── Auth failures → 401, with a clear message ─────────────────────────

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", e.getMessage()));
    }
}
