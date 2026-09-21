package com.blockevidence.backend.dto;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceStatus;

/** C3: one committed version. {@code txId} and {@code timestamp} are the ledger's, not the server's. */
public record HistoryEntryResponse(int version, String txId, Instant timestamp, String action, EvidenceStatus status,
        String metadataCid, String actorId, String actorRole, String reason) {
}
