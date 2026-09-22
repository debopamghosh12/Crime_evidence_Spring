package com.blockevidence.backend.dto;

import java.time.Instant;
import java.util.UUID;

/** A6. */
public record AuditLogResponse(UUID id, UUID userId, String email, String action, String resource,
        String ipAddress, String detail, Instant occurredAt) {
}
