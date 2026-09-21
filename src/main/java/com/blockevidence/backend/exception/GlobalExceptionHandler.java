package com.blockevidence.backend.exception;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * K1: turns every exception that escapes a controller into the ApiError body.
 *
 * <p>Extends ResponseEntityExceptionHandler so Spring's own MVC exceptions (malformed JSON, wrong
 * method, unknown path, unsupported media type ...) also come out as ApiError instead of Spring's
 * default ProblemDetail: every one of them funnels through handleExceptionInternal below.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, violations);
    }

    /**
     * Every framework-raised MVC exception lands here. The code is the HTTP status name
     * (BAD_REQUEST, NOT_FOUND, METHOD_NOT_ALLOWED ...) and the message is fixed text, never
     * ex.getMessage(): for a body-parse failure that message quotes Jackson internals.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String message = status == HttpStatus.BAD_REQUEST ? "Malformed or unreadable request" : status.getReasonPhrase();
        return body(status, status.name(), message, request, List.of());
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<Object> handleAuthenticationFailed(AuthenticationFailedException ex, WebRequest request) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", ex.getMessage(), request, List.of());
    }

    // Method security (@PreAuthorize) throws these from *inside* MVC, so without explicit handlers the
    // catch-all below would turn a legitimate 403 into a 500.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return body(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this action",
                request, List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required", request, List.of());
    }

    @ExceptionHandler(FeatureNotImplementedException.class)
    ResponseEntity<Object> handleNotImplemented(FeatureNotImplementedException ex, WebRequest request) {
        return body(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Full detail goes to the log only; the client gets no stack trace and no exception text.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ApiError error = ApiError.of(500, "INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private static ResponseEntity<Object> body(HttpStatus status, String code, String message, WebRequest request,
            List<ApiError.FieldViolation> violations) {
        String path = request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "";
        ApiError error = new ApiError(Instant.now(), status.value(), code, message, path, violations);
        return ResponseEntity.status(status).body(error);
    }
}
