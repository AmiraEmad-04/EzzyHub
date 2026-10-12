package com.example.ezzyhubai.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String GENERIC_MESSAGE =
            "An internal error occurred. Please try again.";

    private ResponseEntity<Map<String, Object>>
    buildResponse(
            HttpStatus status,
            String error,
            String message) {

        Map<String, Object> body =
                new LinkedHashMap<>();

        body.put(
                "timestamp",
                LocalDateTime.now());

        body.put(
                "status",
                status.value());

        body.put(
                "error",
                error);

        body.put(
                "message",
                message);

        return ResponseEntity
                .status(status)
                .body(body);
    }

    /*
     * Invalid JSON report
     */
    @ExceptionHandler(
            JsonProcessingException.class)
    public ResponseEntity<Map<String, Object>>
    handleJson(
            JsonProcessingException ex) {

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid JSON",
                ex.getOriginalMessage());
    }

    /*
     * Invalid multipart request
     */
    @ExceptionHandler(
            MultipartException.class)
    public ResponseEntity<Map<String, Object>>
    handleMultipart(
            MultipartException ex) {

        log.error("Multipart request error", ex);

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid multipart request",
                GENERIC_MESSAGE);
    }

    /*
     * File errors
     */
    @ExceptionHandler(
            IOException.class)
    public ResponseEntity<Map<String, Object>>
    handleIO(
            IOException ex) {

        log.error("File error", ex);

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "File error",
                GENERIC_MESSAGE);
    }

    /*
     * Database errors
     */
    @ExceptionHandler(
            DataAccessException.class)
    public ResponseEntity<Map<String, Object>>
    handleDatabase(
            DataAccessException ex) {

        log.error("Database error", ex);

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Database error",
                GENERIC_MESSAGE);
    }

    /*
     * Custom generation errors
     */
    @ExceptionHandler(
            GenerationException.class)
    public ResponseEntity<Map<String, Object>>
    handleGeneration(
            GenerationException ex) {

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Generation failed",
                ex.getMessage());
    }

    /*
     * Auth failures → 401, with a clear message
     */
    @ExceptionHandler(
            UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>>
    handleUnauthorized(
            UnauthorizedException ex) {

        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                ex.getMessage());
    }

    /*
     * Everything else
     */
    @ExceptionHandler(
            Exception.class)
    public ResponseEntity<Map<String, Object>>
    handleException(
            Exception ex) {

        log.error("Unhandled exception", ex);

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                GENERIC_MESSAGE);
    }
}
