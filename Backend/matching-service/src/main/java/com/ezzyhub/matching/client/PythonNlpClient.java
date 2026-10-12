package com.ezzyhub.matching.client;

import com.ezzyhub.matching.dto.AstChunkZipResponse;
import com.ezzyhub.matching.dto.BatchPreprocessResponse;
import com.ezzyhub.matching.dto.PreprocessEmbedResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the Python NLP Microservice (Port 8000).
 *
 * Endpoints:
 *   POST /ast-chunk-zip               → AST chunk + embed Java code ZIP
 *   POST /batch-preprocess-and-embed  → embed N requirements in 1 call (v2.2 — PREFERRED)
 *   POST /preprocess-and-embed        → embed 1 requirement (v2.0 — fallback)
 *   GET  /health                      → readiness check
 */
@Slf4j
@Component
public class PythonNlpClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public PythonNlpClient(@Qualifier("pythonWebClient") WebClient webClient,
                           ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    // ── /ast-chunk-zip ─────────────────────────────────────────────────────────

    /**
     * AST-chunk a ZIP of Java source code and embed each chunk with SBERT (384-dim).
     */
    public AstChunkZipResponse chunkAndEmbedCode(MultipartFile file) {
        log.info("Sending ZIP ({} bytes) to Python /ast-chunk-zip", file.getSize());
        long t0 = System.currentTimeMillis();
        try {
            byte[] bytes    = file.getBytes();
            String filename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "code.zip";

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename; }
            }).contentType(MediaType.APPLICATION_OCTET_STREAM);

            AstChunkZipResponse resp = webClient.post()
                    .uri("/ast-chunk-zip")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(AstChunkZipResponse.class)
                    .block();

            long ms = System.currentTimeMillis() - t0;
            if (resp != null)
                log.info("ast-chunk-zip OK: {} files, {} chunks in {}ms",
                        resp.getTotalFiles(), resp.getTotalChunks(), ms);
            return resp;

        } catch (IOException e) {
            throw new RuntimeException("Could not read ZIP file bytes: " + e.getMessage(), e);
        } catch (WebClientResponseException e) {
            log.error("/ast-chunk-zip HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException(
                    "Python /ast-chunk-zip failed (HTTP " + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot reach Python /ast-chunk-zip (port 8000): " + e.getMessage(), e);
        }
    }

    // ── /batch-preprocess-and-embed  (v2.2 — PRIMARY) ─────────────────────────

    /**
     * Embed ALL requirements in a single HTTP call.
     *
     * This is the primary method for the matching pipeline — replaces N serial
     * calls to /preprocess-and-embed with one batched request. Latency drops
     * from O(N × network_RTT) to O(1 × network_RTT).
     *
     * Each text may optionally carry its reqId via the "REQ-ID||text" prefix
     * so the Python service can echo it back for easy mapping.
     *
     *   e.g. "FR-2.1.1||Allow users to import source code from GitHub"
     *
     * Falls back to serial /preprocess-and-embed if the batch endpoint is
     * unavailable (Python service version < 2.2).
     *
     * @param texts list of plain texts OR "REQ-ID||text" prefixed strings
     * @param mode  "requirement" (default) or "code"
     * @return BatchPreprocessResponse with one SingleEmbedResult per input
     */
    public BatchPreprocessResponse batchPreprocessAndEmbed(List<String> texts, String mode) {
        log.info("Calling Python /batch-preprocess-and-embed for {} texts", texts.size());
        long t0 = System.currentTimeMillis();
        try {
            // Build JSON body with Jackson so all text (user-controlled requirement
            // strings) is correctly escaped — including control characters that a
            // hand-rolled escaper missed, which could produce malformed/injected JSON.
            String body = objectMapper.writeValueAsString(Map.of(
                    "texts", texts,
                    "mode", mode != null ? mode : "requirement"));

            BatchPreprocessResponse resp = webClient.post()
                    .uri("/batch-preprocess-and-embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(BatchPreprocessResponse.class)
                    .block();

            long ms = System.currentTimeMillis() - t0;
            if (resp != null)
                log.info("batch-preprocess-and-embed OK: {}/{} succeeded in {}ms",
                        resp.getSuccess(), resp.getTotal(), ms);
            return resp;

        } catch (WebClientResponseException e) {
            log.error("/batch-preprocess-and-embed HTTP {}: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException(
                    "Python /batch-preprocess-and-embed failed (HTTP "
                    + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot reach Python /batch-preprocess-and-embed: " + e.getMessage(), e);
        }
    }

    // ── /preprocess-and-embed  (v2.0 — FALLBACK for single requirements) ──────

    /**
     * Embed a single requirement text.
     * Used as fallback when /batch-preprocess-and-embed is not available,
     * or when embedding an individual requirement outside the batch flow.
     */
    public PreprocessEmbedResponse preprocessAndEmbed(String reqText) {
        log.debug("Calling /preprocess-and-embed for: '{}'",
                reqText.length() > 80 ? reqText.substring(0, 80) + "..." : reqText);
        try {
            // Jackson-serialized body — safe escaping of user-controlled text.
            java.util.Map<String, String> payload = new java.util.HashMap<>();
            payload.put("text", reqText);
            payload.put("mode", "requirement");
            String body = objectMapper.writeValueAsString(payload);

            PreprocessEmbedResponse resp = webClient.post()
                    .uri("/preprocess-and-embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(PreprocessEmbedResponse.class)
                    .block();

            if (resp != null && resp.getSentenceEmbedding() != null)
                log.debug("preprocess-and-embed OK: {} dims, {} kws",
                        resp.getSentenceEmbedding().size(),
                        resp.getKeywords() != null ? resp.getKeywords().size() : 0);
            return resp;

        } catch (WebClientResponseException e) {
            log.error("/preprocess-and-embed HTTP {}: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException(
                    "Python /preprocess-and-embed failed (HTTP " + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot reach Python /preprocess-and-embed: " + e.getMessage(), e);
        }
    }

    // ── /health ───────────────────────────────────────────────────────────────

    /** @return true if Python is UP and models are loaded */
    public boolean isHealthy() {
        try {
            var resp = webClient.get().uri("/health")
                    .retrieve().bodyToMono(java.util.Map.class).block();
            if (resp == null) return false;
            return "UP".equals(resp.get("status"));
        } catch (Exception e) {
            log.warn("Python health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Readiness gate (retry-then-block) ─────────────────────────────────────

    /**
     * Blocks until the Python NLP service reports healthy, retrying a bounded
     * number of times with a fixed delay between attempts. Throws
     * PythonNotReadyException if it is still not ready after all attempts.
     *
     * WHY THIS EXISTS: if Python is down or still loading its models when a
     * match starts, EVERY embedding call falls back to a zero vector and the
     * whole report becomes false-MISSINGs — a silently broken result that looks
     * like real output. Gating the pipeline on readiness converts that silent
     * corruption into a clear, actionable 503 ("service warming up, try again")
     * and gives a cold service a few seconds to finish loading rather than
     * failing instantly.
     *
     * @param maxAttempts  how many health probes to make (>= 1)
     * @param delay        wait between probes
     * @throws PythonNotReadyException if not healthy after maxAttempts
     */
    public void waitUntilReady(int maxAttempts, java.time.Duration delay) {
        int attempts = Math.max(1, maxAttempts);
        for (int i = 1; i <= attempts; i++) {
            if (isHealthy()) {
                if (i > 1) log.info("Python NLP ready on attempt {}/{}", i, attempts);
                return;
            }
            log.warn("Python NLP not ready (attempt {}/{}) — waiting {}ms before retry",
                    i, attempts, delay.toMillis());
            if (i < attempts) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PythonNotReadyException(
                            "Interrupted while waiting for Python NLP service to become ready.");
                }
            }
        }
        throw new PythonNotReadyException(
                "Python NLP service (port 8000) is not ready after " + attempts
                        + " attempts. It may be down or still loading its models. "
                        + "Matching was NOT run to avoid producing a report full of "
                        + "zero-vector false-MISSINGs. Start/await the Python service and retry.");
    }

    /** Thrown when the Python NLP service is not ready; mapped to HTTP 503 by the controller. */
    public static class PythonNotReadyException extends RuntimeException {
        public PythonNotReadyException(String message) { super(message); }
    }

    /**
     * Check if the Python service has the v2.2 batch endpoint.
     * Inspects the /health response for version >= 2.2.0.
     */
    public boolean supportsBatchEmbed() {
        try {
            var resp = webClient.get().uri("/health")
                    .retrieve().bodyToMono(java.util.Map.class).block();
            if (resp == null) return false;
            String version = String.valueOf(resp.getOrDefault("version", "1.0.0"));
            // Parse major.minor and check >= 2.2
            String[] parts = version.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return major > 2 || (major == 2 && minor >= 2);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

}
