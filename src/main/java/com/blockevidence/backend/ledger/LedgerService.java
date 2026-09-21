package com.blockevidence.backend.ledger;

import java.util.List;
import java.util.Optional;

import com.blockevidence.backend.domain.EvidenceStatus;

/**
 * G1, FINAL for Phase 2. The single seam between the application and the blockchain: every ledger
 * read or write goes through this interface (constraint C-01), and only classes in this package may know
 * which ledger sits behind it.
 *
 * <p>Conventions every implementation must honour (the chaincode defines them, see
 * docs/CHAINCODE_DESIGN.md; {@code InMemoryLedgerService} is the executable reference):
 * <ul>
 * <li>Writes carry a {@link LedgerActor} taken from the JWT (C-05) and, except create, the
 *     {@code expectedVersion} the caller last read; a mismatch fails with VERSION_CONFLICT, so a stale
 *     view can never silently overwrite a newer one.</li>
 * <li>A write that the ledger rejects throws {@link LedgerException} with one of the closed set of
 *     {@link LedgerErrorCode}s; an unreachable ledger throws LEDGER_UNAVAILABLE.</li>
 * <li>Nothing here can delete a record (C-02): there is no such method by design.</li>
 * <li>Only ids, hashes and CIDs cross this interface (C-06). Timestamps are the ledger's (C4).</li>
 * </ul>
 * Transfer operations (initiate/accept/reject) are intentionally absent: they are designed and added
 * with D2 in Phase 3, not guessed at now.
 */
public interface LedgerService {

    /** B1/C1: creates version 1 with status COLLECTED. Fails EVIDENCE_EXISTS if the id is taken. */
    LedgerTxResult createEvidence(LedgerNewEvidence evidence, LedgerActor actor);

    /**
     * B4: points the record at a new metadata document. The file fields are immutable and cannot be
     * changed here. {@code reason} is mandatory.
     */
    LedgerTxResult updateEvidence(String evidenceId, int expectedVersion, String newMetadataCid,
            String newMetadataSha256, String reason, LedgerActor actor);

    /** D1 (Phase 3 uses it): ordinary status change. DISPOSED cannot be set here, only via approval. */
    LedgerTxResult updateStatus(String evidenceId, int expectedVersion, EvidenceStatus newStatus, String reason,
            LedgerActor actor);

    /** B5: asks for disposal. Nothing is removed; the record only gains a PENDING request. */
    LedgerTxResult requestDisposal(String evidenceId, int expectedVersion, String reason, LedgerActor actor);

    /** B5: an authorised role approves the pending request; status becomes DISPOSED and the record freezes. */
    LedgerTxResult approveDisposal(String evidenceId, int expectedVersion, String note, LedgerActor actor);

    /** B5: rejects the pending request; the record is otherwise unchanged. */
    LedgerTxResult rejectDisposal(String evidenceId, int expectedVersion, String note, LedgerActor actor);

    /** B3: current state, or empty if no such evidence. */
    Optional<LedgerEvidenceRecord> getEvidence(String evidenceId);

    /** C3/B4: every committed version, oldest first. Throws EVIDENCE_NOT_FOUND for an unknown id. */
    List<LedgerHistoryEntry> getHistory(String evidenceId);

    /**
     * B3: ids of evidence that ever referenced this CID (its file CID or any metadata CID). Several
     * items can share a CID (the same file registered twice), hence a list; empty if none.
     */
    List<String> findEvidenceIdsByCid(String cid);

    /** Feeds the G4 health check. Must not throw and must return quickly. */
    LedgerHealth health();
}
