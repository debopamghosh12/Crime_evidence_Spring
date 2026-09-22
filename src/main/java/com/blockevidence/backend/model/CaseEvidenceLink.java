package com.blockevidence.backend.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * E3. Links one ledger evidence item ({@code evidenceId}, e.g. "EV-...") to one case (table "case_evidence"). Off-chain only
 * (C-06): the ledger record itself still carries just the case's number as a plain string; this row is what turns that string
 * into a real foreign key, the same treatment E1/E2 already give cases. Immutable once written (evidence is never re-cased);
 * there is no update or delete method, matching C-02's spirit even though this table is not evidence itself.
 */
@Entity
@Table(name = "case_evidence")
public class CaseEvidenceLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "evidence_id", nullable = false, unique = true)
    private String evidenceId;

    @Column(name = "linked_by", nullable = false)
    private UUID linkedBy;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected CaseEvidenceLink() {
        // required by JPA
    }

    public CaseEvidenceLink(UUID caseId, String evidenceId, UUID linkedBy, Instant linkedAt) {
        this.caseId = caseId;
        this.evidenceId = evidenceId;
        this.linkedBy = linkedBy;
        this.linkedAt = linkedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public UUID getLinkedBy() {
        return linkedBy;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
