package com.blockevidence.backend.security;

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
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter errorWriter;

    public ApiAuthenticationEntryPoint(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) {
        // RFC 6750: a 401 for a bearer-protected resource should say which scheme is expected.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        errorWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required",
                request.getRequestURI());
    }
}
