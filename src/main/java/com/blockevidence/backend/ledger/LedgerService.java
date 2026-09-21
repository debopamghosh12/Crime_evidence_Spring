package com.blockevidence.backend.ledger;

import java.util.List;
import java.util.Optional;

/**
 * G1: the single seam between the application and the blockchain. Every ledger read or write goes
 * through this interface (constraint C-01); only classes in this package may know which ledger sits
 * behind it, so a web3j/Polygon implementation can replace FabricLedgerService without touching
 * controllers or services.
 *
 * <p>PROVISIONAL: the operation list mirrors the chaincode functions in FEATURE_LIST.md G2, but the
 * exact parameter and return shapes will be revised in Phase 2, when the real chaincode calls force
 * them. {@code rejectTransfer} is added in Phase 3 (D2). Status is a plain String until the D1
 * state machine defines the enum.
 *
 * <p>{@code actorId} is always the acting user's id taken from the authenticated principal, never
 * from a request body (C-05). Per constraint C-06 nothing personal is passed here, only ids,
 * hashes and CIDs.
 */
public interface LedgerService {

    LedgerTxResult createEvidence(String evidenceId, String caseId, String cid, String sha256, String actorId);

    LedgerTxResult updateEvidence(String evidenceId, String newCid, String newSha256, String reason, String actorId);

    LedgerTxResult updateStatus(String evidenceId, String newStatus, String reason, String actorId);

    LedgerTxResult initiateTransfer(String evidenceId, String toUserId, String reason, String actorId);

    LedgerTxResult acceptTransfer(String evidenceId, String actorId);

    Optional<LedgerEvidenceRecord> getEvidence(String evidenceId);

    List<LedgerHistoryEntry> getHistory(String evidenceId);

    /** Feeds the G4 health check. Must not throw and must return quickly. */
    LedgerHealth health();
}
