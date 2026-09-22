package com.blockevidence.backend.sync;

import java.time.Instant;
import java.util.UUID;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.ledger.LedgerAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * G3 (design docs/G3_SYNC_DESIGN.md section 5, section 7). One row per chaincode event, ever. Does two jobs:
 * the {@code tx_id} UNIQUE constraint is the idempotency guard (a redelivered event inserts zero rows and is
 * skipped), and the table itself is the append-only feed H3 reads from - there is no second "processed events"
 * table. Never updated after insert; never deleted (this is a projection artifact, not evidence itself, but the
 * append-only spirit matches C-02 anyway).
 */
@Entity
@Table(name = "evidence_activity")
public class EvidenceActivity {

    @Id
    private UUID id;

    @Column(name = "tx_id", nullable = false, unique = true)
    private String txId;

    @Column(name = "block_number", nullable = false)
    private long blockNumber;

    @Column(name = "evidence_id", nullable = false)
    private String evidenceId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerAction action;

    @Column(name = "case_id")
    private String caseId;

    @Enumerated(EnumType.STRING)
    private EvidenceStatus status;

    @Column(name = "actor_id")
    private String actorId;

    private String actorRole;

    private String reason;

    @Column(name = "ledger_at", nullable = false)
    private Instant ledgerAt;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected EvidenceActivity() {
        // required by JPA
    }

    public UUID getId() {
        return id;
    }

    public String getTxId() {
        return txId;
    }

    public long getBlockNumber() {
        return blockNumber;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public int getVersion() {
        return version;
    }

    public LedgerAction getAction() {
        return action;
    }

    public String getCaseId() {
        return caseId;
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public String getActorId() {
        return actorId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getReason() {
        return reason;
    }

    public Instant getLedgerAt() {
        return ledgerAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
