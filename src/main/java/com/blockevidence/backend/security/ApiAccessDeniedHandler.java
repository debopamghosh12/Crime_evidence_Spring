package com.blockevidence.backend.security;

import com.blockevidence.backend.exception.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * K1 for the security layer: invoked when an authenticated user lacks the authority for a
 * URL-level rule. (Denials from @PreAuthorize arrive as an exception inside MVC and are handled by
 * GlobalExceptionHandler; both produce the same 403 body.)
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter errorWriter;

    public ApiAccessDeniedHandler(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) {
        errorWriter.write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI());
    }
}
