package com.blockevidence.backend.sync;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.ledger.LedgerAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * G3 (design section 5 step 3b, section 7): the current state of one evidence item, for H1 search/filter and H2
 * dashboard aggregates. A pure projection - nothing else writes to it, and it can always be rebuilt by resetting
 * {@code ledger_sync_checkpoint} and replaying the ledger from block 0 (the ledger is the source of truth).
 */
@Entity
@Table(name = "evidence_projection")
public class EvidenceProjection {

    @Id
    @Column(name = "evidence_id")
    private String evidenceId;

    @Column(name = "case_id")
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false)
    private EvidenceType evidenceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EvidenceStatus status;

    @Column(nullable = false)
    private int version;

    @Column(name = "current_custodian")
    private String currentCustodian;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_action", nullable = false)
    private LedgerAction lastAction;

    @Column(name = "last_reason")
    private String lastReason;

    protected EvidenceProjection() {
        // required by JPA
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public String getCaseId() {
        return caseId;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public String getCurrentCustodian() {
        return currentCustodian;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public LedgerAction getLastAction() {
        return lastAction;
    }

    public String getLastReason() {
        return lastReason;
    }
}
