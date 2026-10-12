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
 * Aggregates health status from all six downstream services in parallel.
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

    // NEW: added alongside the new auth-service.
    @Value("${services.auth-service.url:http://localhost:8087}")
    private String authServiceUrl;

    @Value("${services.auth-service.health-path:/api/v1/auth/health}")
    private String authServiceHealthPath;

    // NEW: sonar-analyzer now actually has a working gateway route (see
    // application.yml) — folded into the aggregate health check too.
    @Value("${services.sonar-analyzer.url:http://localhost:8084}")
    private String sonarAnalyzerUrl;

    @Value("${services.sonar-analyzer.health-path:/api/health}")
    private String sonarAnalyzerHealthPath;

    // NEW: ai-suggestions now actually has a working gateway route (see
    // application.yml) — folded into the aggregate health check too.
    @Value("${services.ai-suggestions.url:http://localhost:8088}")
    private String aiSuggestionsUrl;

    @Value("${services.ai-suggestions.health-path:/actuator/health}")
    private String aiSuggestionsHealthPath;

    public GatewayHealthController() {
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024)) // 1 MB for health responses
                .build();
    }

    @GetMapping
    public Mono<Map<String, Object>> health() {
        // Run all five health checks in parallel
        Mono<Map<String, Object>> codeHealth = probe("code-analyzer", codeAnalyzerUrl, codeAnalyzerHealthPath);
        Mono<Map<String, Object>> reqHealth  = probe("requirement-analyzer", reqAnalyzerUrl, reqAnalyzerHealthPath);
        Mono<Map<String, Object>> nlpHealth  = probe("python-nlp", pythonNlpUrl, pythonNlpHealthPath);
        // NEW: added alongside the new auth-service.
        Mono<Map<String, Object>> authHealth = probe("auth-service", authServiceUrl, authServiceHealthPath);
        // NEW: sonar-analyzer now actually has a working gateway route.
        Mono<Map<String, Object>> sonarHealth = probe("sonar-analyzer", sonarAnalyzerUrl, sonarAnalyzerHealthPath);
        // NEW: ai-suggestions now actually has a working gateway route.
        Mono<Map<String, Object>> aiSuggestionsHealth = probe("ai-suggestions", aiSuggestionsUrl, aiSuggestionsHealthPath);

        return Mono.zip(codeHealth, reqHealth, nlpHealth, authHealth, sonarHealth, aiSuggestionsHealth)
                .map(tuple -> {
                    Map<String, Object> services = new LinkedHashMap<>();
                    services.put("code-analyzer",        tuple.getT1());
                    services.put("requirement-analyzer", tuple.getT2());
                    services.put("python-nlp",           tuple.getT3());
                    services.put("auth-service",         tuple.getT4());
                    services.put("sonar-analyzer",       tuple.getT5());
                    services.put("ai-suggestions",       tuple.getT6());

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
