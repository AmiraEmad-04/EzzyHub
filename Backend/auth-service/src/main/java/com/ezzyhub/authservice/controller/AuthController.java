package com.ezzyhub.authservice.controller;

import com.ezzyhub.authservice.dto.AuthResponse;
import com.ezzyhub.authservice.dto.LoginRequest;
import com.ezzyhub.authservice.dto.RegisterRequest;
import com.ezzyhub.authservice.dto.UserDto;
import com.ezzyhub.authservice.security.RateLimiter;
import com.ezzyhub.authservice.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST Controller for account creation and login by email.
 *
 * Reachable directly at /api/v1/auth/** (this service's own port), and via
 * the gateway at /api/auth/** once the auth-service route is added to
 * api-gateway's application.yml (see the accompanying gateway diff).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "EzzyHub account creation and login")
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter;

    // Neither endpoint had any limit before; both are prime brute-force/spam targets
    // (login = credential stuffing, register = account creation spam).
    private static final int LOGIN_MAX_ATTEMPTS = 10;
    private static final int REGISTER_MAX_ATTEMPTS = 10;
    private static final long RATE_LIMIT_WINDOW_MS = TimeUnit.MINUTES.toMillis(1);

    @PostMapping("/register")
    @Operation(
            summary = "Create an account",
            description = "Creates a user by full name, email, and password, and returns a JWT — the account is active immediately, no email confirmation step."
    )
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.tryAcquire("register:" + clientIp(httpRequest), REGISTER_MAX_ATTEMPTS, RATE_LIMIT_WINDOW_MS)) {
            return tooManyRequests();
        }
        log.info("POST /register - email: '{}'", request.getEmail());
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in by email and password", description = "Returns a JWT on success.")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.tryAcquire("login:" + clientIp(httpRequest), LOGIN_MAX_ATTEMPTS, RATE_LIMIT_WINDOW_MS)) {
            return tooManyRequests();
        }
        log.info("POST /login - email: '{}'", request.getEmail());
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<?> tooManyRequests() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "status", 429,
                "error", "Too Many Requests",
                "message", "Too many attempts, please try again shortly"
        ));
    }

    /**
     * SECURITY: deliberately ignores X-Forwarded-For. This service is documented as
     * reachable directly (not only through api-gateway), so there is no trust boundary
     * that guarantees that header wasn't set by the caller itself — trusting it would
     * let anyone bypass the rate limit just by sending a different value on each
     * request (verified live: spoofing X-Forwarded-For reset the limiter entirely).
     * request.getRemoteAddr() is the actual TCP peer address and can't be forged by
     * the client. If this service ends up only ever reached through a trusted proxy
     * that overwrites this header, X-Forwarded-For could be reinstated safely then.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    @GetMapping("/me")
    @Operation(
            summary = "Get the currently logged-in user",
            description = "Reads the Bearer token from the Authorization header. Used by the frontend on page load to check whether a session is still valid."
    )
    public ResponseEntity<UserDto> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UserDto user = authService.getCurrentUser(authorization);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/health")
    @Operation(summary = "Health check endpoint")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "module", "Auth Service",
                "version", "1.0.0"
        ));
    }
}
