package com.ezzyhub.gateway.filter;

import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how many requests a single client (by IP) can send through the gateway per
 * minute, across every route. This is a coarse, defense-in-depth backstop — it doesn't
 * replace endpoint-specific limits like auth-service's own login/register limiter, it
 * just stops one source from flooding every downstream service at once.
 *
 * Uses the actual TCP remote address (exchange.getRequest().getRemoteAddress()), not
 * X-Forwarded-For — that header is client-suppliable and trusting it would let anyone
 * bypass the limit by sending a different value per request (verified this exact bypass
 * live against auth-service's own limiter before switching it to the same approach).
 */
@Component
@RequiredArgsConstructor
public class GlobalRateLimitFilter implements GlobalFilter, Ordered {

    private static final int MAX_REQUESTS_PER_WINDOW = 300;
    private static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(1);

    private final RateLimiter rateLimiter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = resolveClientIp(exchange);

        if (!rateLimiter.tryAcquire(clientIp, MAX_REQUESTS_PER_WINDOW, WINDOW_MS)) {
            return tooManyRequests(exchange);
        }

        return chain.filter(exchange);
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded, please retry shortly\"}"
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
