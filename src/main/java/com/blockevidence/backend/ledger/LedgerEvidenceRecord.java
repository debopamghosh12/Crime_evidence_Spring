package com.blockevidence.backend.ledger;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.security.Role;

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
        Disposal disposal,
        Transfer transfer) {

    /** A record serialised before transfers existed has no "transfer" key; it means "never transferred", not "unknown". */
    public LedgerEvidenceRecord {
        if (transfer == null) {
            transfer = Transfer.none();
        }
    }

    public enum DisposalState {
        NONE, PENDING
    }

    public enum TransferState {
        NONE, PENDING, ACCEPTED, REJECTED, CANCELLED
    }

    /**
     * D2: the most recent custody transfer. Only PENDING blocks a new one; the details of a resolved transfer stay
     * on the record, and every version is in the history, which the custody timeline (D3) is built from. {@code toRole}
     * is the receiver's role as the backend supplied it (C-08). The chaincode returns NONE for records written before
     * transfers existed, and the record's constructor turns an absent value into NONE as well.
     */
    public record Transfer(TransferState state, String from, String to, Role toRole, String reason, String notes,
            Instant initiatedAt, Instant resolvedAt, String resolutionNote) {

        public static Transfer none() {
            return new Transfer(TransferState.NONE, null, null, null, null, null, null, null, null);
        }
    }

    /** {@code requestedBy/requestedAt/reason} are only meaningful while {@code state} is PENDING. */
    public record Disposal(DisposalState state, String requestedBy, Instant requestedAt, String reason) {

        public static Disposal none() {
            return new Disposal(DisposalState.NONE, null, null, null);
        }
    }
}
