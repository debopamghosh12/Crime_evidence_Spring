package com.blockevidence.backend.controller;

import java.util.UUID;

import com.blockevidence.backend.dto.NotificationResponse;
import com.blockevidence.backend.dto.PageResponse;
import com.blockevidence.backend.notification.Notification;
import com.blockevidence.backend.notification.NotificationService;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** H4. Always the CALLING user's own notifications (from the token, C-05) - there is no "notifications for
 *  another user" endpoint. In-app only; email is explicitly out of scope unless trivial (it is not, here: no
 *  mail server is configured), per the owner's instruction. */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "In-app only (no email - no mail server configured). Always the "
        + "caller's own, from the token - there is no endpoint for another user's notifications.")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "My notifications", description = "Any authenticated role - always the caller's own. "
            + "Populated for a custody transfer's receiver, every case member on a status change or disposal "
            + "event, and the current custodian on a tamper alert (the moment a verify() call finds TAMPERED).")
    public PageResponse<NotificationResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = notificationService.list(user.userId(), page, size);
        return new PageResponse<>(result.items().stream().map(NotificationController::toResponse).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Mark read", description = "Any authenticated role - only affects the caller's own "
            + "notification; marking one that belongs to someone else, or does not exist, is a silent no-op.")
    public void markRead(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        notificationService.markRead(user.userId(), id);
    }

    private static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getType().name(), n.getEvidenceId(), n.getCaseId(),
                n.getMessage(), n.getCreatedAt(), n.getReadAt());
    }
}
