package com.blockevidence.backend.audit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

public final class AuditSpecifications {

    private AuditSpecifications() {
    }

    public static Specification<AuditLog> filter(UUID userId, AuditAction action, Instant from, Instant to) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (userId != null) {
                predicates = cb.and(predicates, cb.equal(root.get("userId"), userId));
            }
            if (action != null) {
                predicates = cb.and(predicates, cb.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return predicates;
        };
    }
}
