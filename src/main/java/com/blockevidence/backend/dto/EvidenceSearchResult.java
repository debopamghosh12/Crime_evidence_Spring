package com.blockevidence.backend.dto;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;

/** H1: one row of a search result, served from the off-chain projection (G3), not the ledger. */
public record EvidenceSearchResult(String evidenceId, String caseId, EvidenceType evidenceType,
        EvidenceStatus status, int version, String currentCustodian, String createdBy, Instant createdAt,
        Instant updatedAt, String lastAction, String lastReason) {
}
