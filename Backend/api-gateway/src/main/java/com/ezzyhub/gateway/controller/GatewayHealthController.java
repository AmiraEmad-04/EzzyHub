package com.ezzyhub.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/health
 *
 * Aggregates health status from all three downstream services in parallel.
 * Returns a combined report so the frontend can show a single health panel.
 *
 * Example response:
 * {
 *   "gateway": "UP",
 *   "timestamp": "2026-05-18T12:00:00Z",
 *   "services": {
 *     "code-analyzer":       { "status": "UP",   "url": "http://localhost:8082" },
 *     "requirement-analyzer":{ "status": "DOWN",  "url": "http://localhost:8081", "error": "..." },
 *     "python-nlp":          { "status": "UP",   "url": "http://localhost:8000",
 *                              "sbert_loaded": true, "word2vec_loaded": false }
 *   },
 *   "allUp": false
 * }
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
public class GatewayHealthController {

    private final WebClient webClient;

    @Value("${services.code-analyzer.url:http://localhost:8082}")
    private String codeAnalyzerUrl;

    @Value("${services.code-analyzer.health-path:/api/v1/health}")
    private String codeAnalyzerHealthPath;

    @Value("${services.requirement-analyzer.url:http://localhost:8081}")
    private String reqAnalyzerUrl;

    @Value("${services.requirement-analyzer.health-path:/api/v1/requirements/health}")
    private String reqAnalyzerHealthPath;

    @Value("${services.python-nlp.url:http://localhost:8000}")
    private String pythonNlpUrl;

    @Value("${services.python-nlp.health-path:/health}")
    private String pythonNlpHealthPath;

    public GatewayHealthController() {
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024)) // 1 MB for health responses
                .build();
    }

    @GetMapping
    public Mono<Map<String, Object>> health() {
        // Run all three health checks in parallel
        Mono<Map<String, Object>> codeHealth = probe("code-analyzer", codeAnalyzerUrl, codeAnalyzerHealthPath);
        Mono<Map<String, Object>> reqHealth  = probe("requirement-analyzer", reqAnalyzerUrl, reqAnalyzerHealthPath);
        Mono<Map<String, Object>> nlpHealth  = probe("python-nlp", pythonNlpUrl, pythonNlpHealthPath);

        return Mono.zip(codeHealth, reqHealth, nlpHealth)
                .map(tuple -> {
                    Map<String, Object> services = new LinkedHashMap<>();
                    services.put("code-analyzer",        tuple.getT1());
                    services.put("requirement-analyzer", tuple.getT2());
                    services.put("python-nlp",           tuple.getT3());

                    boolean allUp = services.values().stream()
                            .map(v -> ((Map<?, ?>) v).get("status"))
                            .allMatch("UP"::equals);

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("gateway",   "UP");
                    result.put("timestamp", Instant.now().toString());
                    result.put("services",  services);
                    result.put("allUp",     allUp);
                    return result;
                });
    }

    /**
     * Probe a single downstream service health endpoint.
     * Returns a map with at least "status" and "url".
     * Times out after 4 seconds so a dead service doesn't block the aggregate response.
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> probe(String name, String baseUrl, String healthPath) {
        return webClient.get()
                .uri(baseUrl + healthPath)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(4))
                .map(body -> {
                    Map<String, Object> svc = new LinkedHashMap<>();
                    svc.put("status", body.getOrDefault("status", "UP").toString());
                    svc.put("url",    baseUrl);
                    // Pass through extra fields (sbert_loaded, word2vec_loaded for Python)
                    body.forEach((k, v) -> {
                        if (!"status".equals(k)) svc.put(k.toString(), v);
                    });
                    return svc;
                })
                .onErrorResume(ex -> {
                    log.warn("Health probe failed for {}: {}", name, ex.getMessage());
                    Map<String, Object> svc = new LinkedHashMap<>();
                    svc.put("status", "DOWN");
                    svc.put("url",    baseUrl);
                    svc.put("error",  ex.getMessage());
                    return Mono.just(svc);
                });
    }
}
