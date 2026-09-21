package com.blockevidence.backend.ledger;

import com.blockevidence.backend.security.Role;

/**
 * Who is performing a ledger write. Always built from the authenticated principal, never from a
 * request body (C-05). In Phase 2 the chaincode trusts these two values as arguments; from Phase 3
 * (A2) it will read the role from the caller's certificate instead (CHAINCODE_DESIGN.md section 5).
 */
public record LedgerActor(String userId, Role role) {
}
