package com.blockevidence.backend.security;

import com.blockevidence.backend.audit.AuditAction;
import com.blockevidence.backend.audit.AuditService;
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
 * GlobalExceptionHandler; both produce the same 403 body and both record an A6 audit row.)
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter errorWriter;
    private final AuditService audit;

    public ApiAccessDeniedHandler(ApiErrorWriter errorWriter, AuditService audit) {
        this.errorWriter = errorWriter;
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) {
        audit.recordDenied(AuditAction.ACCESS_DENIED, request.getRequestURI(), accessDeniedException.getMessage(),
                request.getRemoteAddr());
        errorWriter.write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI());
    }
}
