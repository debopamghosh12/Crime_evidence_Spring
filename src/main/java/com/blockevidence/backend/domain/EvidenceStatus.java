package com.blockevidence.backend.domain;

/**
 * Lifecycle status of an evidence item (D1). Shared by service, ledger and DTOs, hence in domain/.
 *
 * <p>The transition table is PROVISIONAL and is finalised with D1 in Phase 3 (docs/CHAINCODE_DESIGN.md
 * section 4). DISPOSED is deliberately not reachable through {@link #canMoveTo}: it is entered only by
 * an approved disposal (B5), never by an ordinary status change.
 */
public enum EvidenceStatus {
    COLLECTED,
    PROCESSING,
    ANALYZED,
    ARCHIVED,
    RELEASED,
    DISPOSED;

    /** Ordinary forward-only status changes: COLLECTED -> PROCESSING -> ANALYZED -> ARCHIVED -> RELEASED. */
    public boolean canMoveTo(EvidenceStatus target) {
        return switch (this) {
            case COLLECTED -> target == PROCESSING;
            case PROCESSING -> target == ANALYZED;
            case ANALYZED -> target == ARCHIVED;
            case ARCHIVED -> target == RELEASED;
            case RELEASED, DISPOSED -> false;
        };
    }
}
