package com.blockevidence.backend.exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises an ApiError straight onto the servlet response. Needed because Spring Security's
 * entry point and access-denied handler run inside the filter chain, before DispatcherServlet, so
 * GlobalExceptionHandler cannot produce their response body.
 */
@Component
public class ApiErrorWriter {

    private final JsonMapper jsonMapper;

    public ApiErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String message, String path) {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            jsonMapper.writeValue(response.getOutputStream(), ApiError.of(status.value(), code, message, path));
        } catch (IOException e) {
            // The client went away mid-write; there is nobody left to report the error to.
            throw new UncheckedIOException(e);
        }
    }
}
