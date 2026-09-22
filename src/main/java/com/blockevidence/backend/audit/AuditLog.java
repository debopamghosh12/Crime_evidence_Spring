package com.blockevidence.backend.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A6. One row per audit-worthy event. {@code userId} is null only for AUTH_FAILED with no verifiable principal. */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    private String resource;

    @Column(name = "ip_address")
    private String ipAddress;

    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLog() {
        // required by JPA
    }

    public AuditLog(UUID id, UUID userId, String email, AuditAction action, String resource, String ipAddress,
            String detail, Instant occurredAt) {
        this.id = id;
        this.userId = userId;
        this.email = email;
        this.action = action;
        this.resource = resource;
        this.ipAddress = ipAddress;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getResource() {
        return resource;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
