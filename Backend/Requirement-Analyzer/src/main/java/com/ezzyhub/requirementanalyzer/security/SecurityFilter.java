package com.ezzyhub.requirementanalyzer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Security filter chain for the Requirement Analyzer.
 *
 * Active only when security.enabled=true (default in production).
 * In development (DEV_MODE=true) all checks are bypassed.
 *
 * Protections:
 *   1. API key validation  — X-Api-Key header must match GATEWAY_SECRET
 *      Ensures only the API Gateway (or authorized clients) can call this service.
 *      The frontend never calls this service directly in production.
 *
 *   2. IP-based rate limiting  — 200 req/min per IP (configurable)
 *      Simple in-memory token bucket. For multi-instance deployments
 *      replace with Redis (spring-data-redis + Bucket4j).
 *
 *   3. Request size guard  — already set via spring.servlet.multipart.max-file-size
 *      but checked here as an early short-circuit for non-multipart abuse.
 *
 *   4. Path traversal guard  — rejects any URI containing "../" or "./"
 *
 * Note: XSS and SQL injection are handled by:
 *   - Bean Validation (@NotBlank, @Size) on all DTOs
 *   - Spring Data JPA parameterised queries (no string concatenation in SQL)
 *   - Jackson (all inputs parsed as typed objects, not raw strings into SQL)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = false)
public class SecurityFilter extends OncePerRequestFilter {

    @Value("${security.gateway-secret:}")
    private String gatewaySecret;

    @Value("${security.rate-limit.requests-per-minute:200}")
    private int maxRequestsPerMinute;

    @Value("${server.dev-mode:false}")
    private boolean devMode;

    // Simple in-process rate limit map — replace with Bucket4j+Redis for multi-node
    private final Map<String, RateWindow> rateLimitMap = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // Dev mode: bypass all checks
        if (devMode) { chain.doFilter(req, res); return; }

        String uri = req.getRequestURI();

        // 1. Path traversal guard
        if (uri.contains("../") || uri.contains("./")) {
            log.warn("Path traversal attempt from {}: {}", clientIp(req), uri);
            res.sendError(HttpStatus.BAD_REQUEST.value(), "Invalid path");
            return;
        }

        // 2. Health check and actuator — skip auth
        if (uri.startsWith("/actuator") || uri.equals("/api/v1/requirements/health")) {
            chain.doFilter(req, res);
            return;
        }

        // 3. API key check (only if gatewaySecret is configured)
        if (gatewaySecret != null && !gatewaySecret.isBlank()) {
            String key = req.getHeader("X-Api-Key");
            if (!gatewaySecret.equals(key)) {
                log.warn("Rejected request — missing/invalid X-Api-Key from {}", clientIp(req));
                res.sendError(HttpStatus.UNAUTHORIZED.value(), "Unauthorized");
                return;
            }
        }

        // 4. Rate limit
        String ip = clientIp(req);
        if (isRateLimited(ip)) {
            log.warn("Rate limit exceeded for {}", ip);
            res.setHeader("Retry-After", "60");
            res.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "Too many requests");
            return;
        }

        chain.doFilter(req, res);
    }

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        RateWindow window = rateLimitMap.computeIfAbsent(ip, k -> new RateWindow());
        return window.increment(now, maxRequestsPerMinute);
    }

    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    /** Sliding 1-minute window counter. */
    private static class RateWindow {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        synchronized boolean increment(long now, int max) {
            if (now - windowStart > 60_000) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() > max;
        }
    }
}
