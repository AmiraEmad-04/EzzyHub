package com.ezzyhub.requirementanalyzer.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler — returns structured JSON errors.
 *
 * SECURITY: Stack traces NEVER included in HTTP responses (they leak
 * implementation details to attackers). Full traces are logged server-side only.
 *
 * Response shape:
 * {
 *   "timestamp": "2026-05-18T12:00:00Z",
 *   "status":    422,
 *   "error":     "Unprocessable Entity",
 *   "message":   "Semantic validation failed: text too short",
 *   "path":      "/api/v1/requirements/analyze"    (only in dev mode)
 * }
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Value("${server.dev-mode:false}")
    private boolean devMode;

    @ExceptionHandler(InvalidRequirementException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequirement(
            InvalidRequirementException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        // These are expected — INFO level, no stack trace needed
        log.info("Invalid requirement from {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.info("Validation error at {}: {}", request.getRequestURI(), message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleFileTooLarge(
            MaxUploadSizeExceededException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        log.warn("Upload too large at {}", request.getRequestURI());
        return build(HttpStatus.PAYLOAD_TOO_LARGE,
                "File size exceeds the maximum allowed limit (1 GB)", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        log.warn("Bad argument at {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(
            Exception ex,
            jakarta.servlet.http.HttpServletRequest request) {
        // Unexpected — log full stack trace server-side, return generic message to client
        log.error("Unexpected error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        String msg = devMode
                ? "Internal error: " + ex.getMessage()  // show detail in dev
                : "An internal error occurred";           // hide detail in prod
        return build(HttpStatus.INTERNAL_SERVER_ERROR, msg, request);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> build(
            HttpStatus status, String message,
            jakarta.servlet.http.HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status",    status.value());
        body.put("error",     status.getReasonPhrase());
        body.put("message",   message);
        if (devMode) {
            body.put("path", request.getRequestURI());
        }
        return ResponseEntity.status(status).body(body);
    }
}
