package com.ezzyhub.requirementanalyzer.exception;

/**
 * Thrown when a requirement fails validation (Phase 1).
 */
public class InvalidRequirementException extends RuntimeException {

    public InvalidRequirementException(String message) {
        super(message);
    }
}
