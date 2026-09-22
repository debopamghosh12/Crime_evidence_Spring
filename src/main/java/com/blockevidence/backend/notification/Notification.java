package com.blockevidence.backend.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** H4. One in-app notification for one recipient. Email is explicitly out of scope (not trivial here: no mail
 *  server configured), per the owner's instruction. */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(name = "evidence_id")
    private String evidenceId;

    @Column(name = "case_id")
    private String caseId;

    @Column(nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
        // required by JPA
    }

    public Notification(UUID id, UUID userId, NotificationType type, String evidenceId, String caseId,
            String message, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.evidenceId = evidenceId;
        this.caseId = caseId;
        this.message = message;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void markRead(Instant at) {
        if (this.readAt == null) {
            this.readAt = at;
        }
    }
}
