package com.blockevidence.backend.ledger;

/**
 * Connectivity report from a LedgerService. A dedicated type (rather than a boolean) because the
 * Phase 1 stub is neither up nor down: it is UNKNOWN, and the health endpoint should say so
 * honestly. It also keeps actuator types out of the LedgerService contract.
 */
public record LedgerHealth(State state, String detail) {

    public enum State {
        UP, DOWN, UNKNOWN
    }
}
