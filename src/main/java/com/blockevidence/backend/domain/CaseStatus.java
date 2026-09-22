package com.blockevidence.backend.domain;

/**
 * E1. A case is OPEN when created. There is deliberately no endpoint that changes it yet: closing and reopening, and the guard that
 * blocks new evidence on a closed case, are E4 (not in Phase 3).
 */
public enum CaseStatus {
    OPEN,
    CLOSED
}
