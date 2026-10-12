package com.ezzyhub.requirementanalyzer.llm;

/**
 * Wraps any transport/HTTP failure from an LlmClient implementation, so
 * callers never need to know or catch a vendor-specific exception type
 * (e.g. Spring's WebClientResponseException).
 */
public class LlmClientException extends RuntimeException {
    public LlmClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
