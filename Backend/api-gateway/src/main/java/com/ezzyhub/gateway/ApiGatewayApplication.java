package com.ezzyhub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EzzyHub API Gateway — single entry point for all microservices.
 *
 * Port: 8085
 *
 * Route map (all paths relative to gateway root):
 *
 *   POST  /api/code/analyze               → Code Analyzer  :8082 /api/v1/analyze
 *   GET   /api/code/health                → Code Analyzer  :8082 /api/v1/health
 *   GET   /api/code/info                  → Code Analyzer  :8082 /api/v1/info
 *
 *   POST  /api/requirements/analyze       → Req Analyzer   :8081 /api/v1/requirements/analyze
 *   POST  /api/requirements/analyze/batch → Req Analyzer   :8081 /api/v1/requirements/analyze/batch
 *   GET   /api/requirements               → Req Analyzer   :8081 /api/v1/requirements
 *   GET   /api/requirements/{reqId}       → Req Analyzer   :8081 /api/v1/requirements/{reqId}
 *   DELETE /api/requirements/{id}         → Req Analyzer   :8081 /api/v1/requirements/{id}
 *   GET   /api/requirements/health        → Req Analyzer   :8081 /api/v1/requirements/health
 *
 *   POST  /api/match                      → Req Analyzer   :8081 /api/v1/match
 *
 *   POST  /api/nlp/preprocess             → Python NLP     :8000 /preprocess
 *   POST  /api/nlp/embed                  → Python NLP     :8000 /embed
 *   POST  /api/nlp/preprocess-and-embed   → Python NLP     :8000 /preprocess-and-embed
 *   GET   /api/nlp/health                 → Python NLP     :8000 /health
 *
 *   GET   /api/health                     → Gateway aggregate health (GatewayHealthController)
 */
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
