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
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @PreAuthorize(Permissions.READ_AUDIT_LOG)
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
