package com.blockevidence.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for exceptions that carry their own HTTP status and machine code. GlobalExceptionHandler maps
 * any ApiException generically, so ledger/ and storage/ can define their own errors (with status and
 * code) without exception/ importing them, which keeps the dependency rules in ARCHITECTURE.md intact.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
