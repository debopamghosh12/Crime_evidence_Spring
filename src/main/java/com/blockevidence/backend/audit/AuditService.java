package com.blockevidence.backend.audit;

import java.time.Clock;
import java.util.UUID;

import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A6. Every write here is its own small, independent transaction (not idempotency-guarded like G3's sync: an
 * HTTP request is not redelivered the way a ledger event can be, so there is no equivalent risk).
 *
 * <p>{@link #recordAccess} is the one instrumented for the Phase 1 access-token-lag pairing the owner asked for:
 * it looks up the CURRENT {@code user.enabled} state at write time (a fresh read, not trusting the JWT's claims,
 * which say nothing about deactivation) and records {@link AuditAction#TOKEN_USED_AFTER_DEACTIVATION} instead of
 * the ordinary VIEW/DOWNLOAD when the acting user has since been disabled. This does not change what the request
 * itself does (the TTL gap is not being fixed here, docs/G3_SYNC_DESIGN.md section 11) - only what gets logged.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final UserRepository users;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, UserRepository users, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.clock = clock;
    }

    /** VIEW or DOWNLOAD of one resource by the currently authenticated user (read from the security context, not
     *  a parameter, so callers need no signature change). Silently does nothing if somehow called unauthenticated. */
    @Transactional
    public void recordAccess(String resource, boolean download) {
        AuthenticatedUser user = currentUser();
        if (user == null) {
            return;
        }
        boolean stillEnabled = users.findById(user.userId()).map(u -> u.isEnabled()).orElse(false);
        AuditAction action = stillEnabled ? (download ? AuditAction.DOWNLOAD : AuditAction.VIEW)
                : AuditAction.TOKEN_USED_AFTER_DEACTIVATION;
        save(user.userId(), user.email(), action, resource, download ? "download" : "view", currentIp());
    }

    /**
     * A request that never reached a controller method (401 from the security filter chain, BEFORE
     * DispatcherServlet - {@link org.springframework.web.context.request.RequestContextHolder} is not
     * reliably populated there yet, unlike in {@link #recordAccess}, so the caller passes the IP explicitly).
     */
    @Transactional
    public void recordDenied(AuditAction action, String resource, String detail, String ip) {
        AuthenticatedUser user = currentUser();
        save(user == null ? null : user.userId(), user == null ? null : user.email(), action, resource, detail, ip);
    }

    /** Same as above, for a denial raised INSIDE MVC (e.g. @PreAuthorize -> GlobalExceptionHandler), where the
     *  request context is available. */
    @Transactional
    public void recordDenied(AuditAction action, String resource, String detail) {
        recordDenied(action, resource, detail, currentIp());
    }

    private void save(UUID userId, String email, AuditAction action, String resource, String detail, String ip) {
        repository.save(new AuditLog(UUID.randomUUID(), userId, email, action, resource, ip, detail, clock.instant()));
    }

    private static AuthenticatedUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedUser u ? u : null;
    }

    /** No X-Forwarded-For handling: this project runs without a reverse proxy in front of it (noted as a limit,
     *  docs/KNOWN_GAPS.md); the direct socket address is what there is to record. */
    private static String currentIp() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest request = servletAttrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
