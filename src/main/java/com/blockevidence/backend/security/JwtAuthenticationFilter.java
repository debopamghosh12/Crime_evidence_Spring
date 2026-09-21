package com.blockevidence.backend.security;

import java.io.IOException;
import java.util.List;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A1: reads {@code Authorization: Bearer <jwt>}, and if the token verifies, puts an
 * AuthenticatedUser into the SecurityContext for the rest of the request.
 *
 * <p>An absent or invalid token is NOT an error here: the filter just leaves the context empty and
 * carries on. Whether that is a problem is decided later by the authorization rules in
 * SecurityConfig (public routes still work, protected ones get a 401 from the entry point). That
 * keeps one place, not two, that decides who needs to be logged in.
 *
 * <p>Built by SecurityConfig with {@code new}, deliberately not a @Component: a Filter bean would be
 * auto-registered a second time as a plain servlet filter outside the security chain.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                AuthenticatedUser user = jwtService.parse(header.substring(BEARER_PREFIX.length()).trim());
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        user, null, List.of(new SimpleGrantedAuthority(user.role().authority())));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            } catch (JwtException | IllegalArgumentException e) {
                // Log the exception type only: the message can echo parts of the rejected token.
                log.debug("Rejected bearer token: {}", e.getClass().getSimpleName());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
