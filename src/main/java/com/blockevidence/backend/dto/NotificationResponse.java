package com.blockevidence.backend.dto;

import java.time.Instant;
import java.util.UUID;

/** H4. */
public record NotificationResponse(UUID id, String type, String evidenceId, String caseId, String message,
        Instant createdAt, Instant readAt) {
}
