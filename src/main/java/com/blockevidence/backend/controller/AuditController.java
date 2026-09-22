package com.blockevidence.backend.controller;

import java.time.Instant;
import java.util.UUID;

import com.blockevidence.backend.audit.AuditAction;
import com.blockevidence.backend.audit.AuditLog;
import com.blockevidence.backend.audit.AuditLogRepository;
import com.blockevidence.backend.audit.AuditSpecifications;
import com.blockevidence.backend.dto.AuditLogResponse;
import com.blockevidence.backend.dto.PageResponse;
import com.blockevidence.backend.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A6. ADMIN and AUDITOR only (Permissions.READ_AUDIT_LOG): the log itself names users and IP addresses, so its
 * read access is narrower than ordinary evidence reads. Filtering is done inline (a JPA Specification built
 * straight from the query params) rather than through a separate service, since there is no business logic here
 * beyond "read what was written" - matches the read-only, no-service-needed simplicity of the resource.
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit", description = "Access log: every evidence VIEW/DOWNLOAD and every failed-auth/denied "
        + "attempt, with user, resource, time and IP. Role: ADMIN or AUDITOR only.")
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @PreAuthorize(Permissions.READ_AUDIT_LOG)
    @Operation(summary = "Search the audit log", description = """
            Role: ADMIN or AUDITOR only - narrower than ordinary evidence reads, since this log names users \
            and IP addresses. `action` includes VIEW, DOWNLOAD, ACCESS_DENIED, AUTH_FAILED and \
            TOKEN_USED_AFTER_DEACTIVATION (a still-valid access token used after its owner was deactivated - \
            the request itself still succeeds, this is visibility only, A6/Phase-1 pairing). `size` capped at \
            200, newest first.""")
    public PageResponse<AuditLogResponse> list(@RequestParam(required = false) UUID userId,
            @RequestParam(required = false) AuditAction action, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Specification<AuditLog> spec = AuditSpecifications.filter(userId, action, from, to);
        var result = repository.findAll(spec, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "occurredAt")));
        return PageResponse.of(result.map(AuditController::toResponse));
    }

    private static AuditLogResponse toResponse(AuditLog a) {
        return new AuditLogResponse(a.getId(), a.getUserId(), a.getEmail(), a.getAction().name(), a.getResource(),
                a.getIpAddress(), a.getDetail(), a.getOccurredAt());
    }
}
