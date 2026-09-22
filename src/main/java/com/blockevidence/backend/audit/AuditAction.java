package com.blockevidence.backend.audit;

/**
 * A6. {@code TOKEN_USED_AFTER_DEACTIVATION} is deliberately distinct from {@code VIEW}: it is what the same
 * request would otherwise have logged, EXCEPT the acting user's account is disabled - the specific visibility
 * gap the owner asked to close (the Phase 1 access-token-lag limitation: a token can stay valid for up to 15
 * minutes after deactivation; this does not fix that TTL, it makes any use of it during that window visible).
 */
public enum AuditAction {
    VIEW,
    DOWNLOAD,
    ACCESS_DENIED,
    AUTH_FAILED,
    TOKEN_USED_AFTER_DEACTIVATION
}
