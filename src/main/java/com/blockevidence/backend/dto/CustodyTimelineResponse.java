package com.blockevidence.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * D3: who has held the evidence, when and why, oldest first, with the ledger transaction of each step. Built from the
 * ledger's history, so it cannot disagree with it. Users are shown by id only (C-06 keeps names off the ledger).
 */
public record CustodyTimelineResponse(String evidenceId, String currentCustodian, PendingTransfer pendingTransfer,
        List<CustodyEvent> events) {

    public record PendingTransfer(String from, String to, String reason, String notes, Instant initiatedAt) {
    }

    /**
     * {@code type}: CUSTODY_STARTED, TRANSFER_INITIATED, TRANSFER_ACCEPTED, TRANSFER_REJECTED, TRANSFER_CANCELLED.
     * {@code custodianAfter} is who held the evidence once this step was committed. Only ACCEPTED changes it.
     */
    public record CustodyEvent(int version, String txId, Instant timestamp, String type, String fromUser, String toUser,
            String reason, String notes, String resolutionNote, String actorId, String actorRole, String custodianAfter) {
    }
}
