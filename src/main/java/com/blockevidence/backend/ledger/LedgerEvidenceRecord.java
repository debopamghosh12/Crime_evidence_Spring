package com.blockevidence.backend.ledger;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;

/**
 * The on-ledger state of one evidence item at one version (docs/CHAINCODE_DESIGN.md section 3).
 * Contains no personal data (C-06): user ids are opaque UUIDs. All timestamps are the ledger
 * transaction time, never the server clock (C4). {@code fileCid/fileSha256/fileSize} are null for
 * PHYSICAL evidence and never change after creation.
 */
public record LedgerEvidenceRecord(
        String evidenceId,
        String caseId,
        EvidenceType evidenceType,
        EvidenceStatus status,
        int version,
        String metadataCid,
        String metadataSha256,
        String fileCid,
        String fileSha256,
        Long fileSize,
        String createdBy,
        String createdByRole,
        Instant createdAt,
        String updatedBy,
        String updatedByRole,
        Instant updatedAt,
        LedgerAction lastAction,
        String lastReason,
        String currentCustodian,
        Disposal disposal) {

    public enum DisposalState {
        NONE, PENDING
    }

    /** {@code requestedBy/requestedAt/reason} are only meaningful while {@code state} is PENDING. */
    public record Disposal(DisposalState state, String requestedBy, Instant requestedAt, String reason) {

        public static Disposal none() {
            return new Disposal(DisposalState.NONE, null, null, null);
        }
    }
}
