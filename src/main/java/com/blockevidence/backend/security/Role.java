package com.blockevidence.backend.security;

/**
 * A3. One role per user. The constant names are stored in the users.role column and (prefixed
 * "ROLE_") become the Spring authority, so hasRole('JUDGE') in @PreAuthorize matches JUDGE. Renaming
 * a constant needs a Flyway migration; the chaincode ACL (Phase 2) will use the same names.
 */
public enum Role {
    COLLECTOR,
    FORENSIC_ANALYST,
    PROSECUTOR,
    JUDGE,
    AUDITOR,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
