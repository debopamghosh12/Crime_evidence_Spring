package com.blockevidence.backend.security;

import com.blockevidence.backend.audit.AuditAction;
import com.blockevidence.backend.audit.AuditService;
import com.blockevidence.backend.exception.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * K1 for the security layer: invoked by Spring Security when an unauthenticated request reaches a
 * protected route (no token, expired token, bad token). Emits the standard ApiError as a 401.
 *
 * <p>A6: also records a failed-attempt audit row. No verifiable principal exists at this point (that is
 * exactly why we are here), so it is recorded with a null user - the request path and remote address are
 * what there is to know about who tried.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter errorWriter;
    private final AuditService audit;

    public ApiAuthenticationEntryPoint(ApiErrorWriter errorWriter, AuditService audit) {
        this.errorWriter = errorWriter;
        this.audit = audit;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) {
        // RFC 6750: a 401 for a bearer-protected resource should say which scheme is expected.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        audit.recordDenied(AuditAction.AUTH_FAILED, request.getRequestURI(), authException.getMessage(),
                request.getRemoteAddr());
        errorWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required",
                request.getRequestURI());
    }
}
