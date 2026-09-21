package com.blockevidence.backend.security;

/**
 * A3: the Spring-side permission table for evidence operations, as {@code @PreAuthorize} expressions.
 * This is the FIRST of two checks: the ledger checks the same table again (CHAINCODE_DESIGN.md
 * section 4; InMemoryLedgerService.CAN_*). Keep the three in step. ADMIN and AUDITOR can read but hold
 * no write permission at all (A4: an Admin cannot edit or delete evidence).
 */
public final class Permissions {

    public static final String REGISTER_OR_UPDATE_EVIDENCE = "hasAnyRole('COLLECTOR', 'FORENSIC_ANALYST')";
    public static final String REQUEST_DISPOSAL = "hasAnyRole('COLLECTOR', 'PROSECUTOR')";
    public static final String DECIDE_DISPOSAL = "hasRole('JUDGE')";
    /** Any authenticated user. Case-level restriction (A5) is not built yet, see FEATURE_LIST.md. */
    public static final String READ_EVIDENCE = "isAuthenticated()";

    private Permissions() {
    }
}
