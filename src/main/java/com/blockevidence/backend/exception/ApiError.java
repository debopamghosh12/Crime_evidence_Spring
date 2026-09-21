package com.blockevidence.backend.exception;

import java.time.Instant;
import java.util.List;

/**
 * The single body returned for every non-2xx response (K1). Written by GlobalExceptionHandler for
 * errors raised inside MVC, and by ApiErrorWriter for the 401/403 raised in the security filter
 * chain, which never reaches @RestControllerAdvice.
 *
 * <p>{@code error} is a stable machine-readable code that clients may switch on; {@code message} is
 * human text and may change. {@code fieldErrors} is always present and empty unless the code is
 * VALIDATION_FAILED.
 */
public record ApiError(Instant timestamp, int status, String error, String message, String path,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, List.of());
    }
}
