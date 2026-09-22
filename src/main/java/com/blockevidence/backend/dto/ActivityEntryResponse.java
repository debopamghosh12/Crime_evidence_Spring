package com.blockevidence.backend.dto;

import java.time.Instant;

/** H3: one row of the activity feed, from evidence_activity (G3). */
public record ActivityEntryResponse(String txId, String evidenceId, String caseId, int version, String action,
        String actorId, String actorRole, String reason, Instant ledgerAt) {
}
