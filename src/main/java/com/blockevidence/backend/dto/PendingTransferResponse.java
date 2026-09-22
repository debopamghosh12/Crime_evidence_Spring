package com.blockevidence.backend.dto;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceStatus;

/** D2: one item waiting for the current user to accept or reject. {@code version} is what to send back as expectedVersion. */
public record PendingTransferResponse(String evidenceId, String caseId, EvidenceStatus status, int version, String fromUser,
        String reason, String notes, Instant initiatedAt) {
}
