package com.blockevidence.backend.model;

import java.time.Instant;
import java.util.UUID;

import com.blockevidence.backend.domain.CaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * E1. A case (table "cases"). Named CaseFile because "Case" reads like the Java keyword. Off-chain only: the ledger sees just
 * {@code caseNumber}, as the caseId string on evidence.
 */
@Entity
@Table(name = "cases")
public class CaseFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "case_number", nullable = false)
    private String caseNumber;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "lead_officer_id", nullable = false)
    private UUID leadOfficerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CaseFile() {
        // required by JPA
    }

    public CaseFile(String caseNumber, String title, String description, UUID leadOfficerId, UUID createdBy,
            Instant createdAt) {
        this.caseNumber = caseNumber;
        this.title = title;
        this.description = description;
        this.leadOfficerId = leadOfficerId;
        this.status = CaseStatus.OPEN;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getCaseNumber() {
        return caseNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public UUID getLeadOfficerId() {
        return leadOfficerId;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void update(String title, String description, UUID leadOfficerId) {
        if (title != null) {
            this.title = title;
        }
        if (description != null) {
            this.description = description;
        }
        if (leadOfficerId != null) {
            this.leadOfficerId = leadOfficerId;
        }
    }
}
