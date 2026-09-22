package com.blockevidence.backend.model;

import java.time.Instant;
import java.util.UUID;

import com.blockevidence.backend.domain.CaseRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** E2. One user's membership of one case, with their role on that case. Unique per (case, user). */
@Entity
@Table(name = "case_members")
public class CaseMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_role", nullable = false)
    private CaseRole caseRole;

    @Column(name = "added_by", nullable = false)
    private UUID addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected CaseMember() {
        // required by JPA
    }

    public CaseMember(UUID caseId, UUID userId, CaseRole caseRole, UUID addedBy, Instant addedAt) {
        this.caseId = caseId;
        this.userId = userId;
        this.caseRole = caseRole;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getUserId() {
        return userId;
    }

    public CaseRole getCaseRole() {
        return caseRole;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    /** Changes the role on the case in place. Used when the lead officer changes; a member is never deleted and re-inserted for that. */
    public void changeRole(CaseRole newRole) {
        this.caseRole = newRole;
    }
}
