package com.blockevidence.backend.sync;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/** H1 (design docs/G3_SYNC_DESIGN.md section 11): a Specification per filter, combined with AND. No full-text
 *  index infrastructure at this project's scale, so {@code q} is a simple case-insensitive ILIKE. */
public final class EvidenceProjectionSpecifications {

    private EvidenceProjectionSpecifications() {
    }

    public static Specification<EvidenceProjection> filter(String caseId, EvidenceStatus status,
            EvidenceType evidenceType, String officer, Instant from, Instant to, String q) {
        return (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (caseId != null && !caseId.isBlank()) {
                p = cb.and(p, cb.equal(cb.lower(root.get("caseId")), caseId.toLowerCase()));
            }
            if (status != null) {
                p = cb.and(p, cb.equal(root.get("status"), status));
            }
            if (evidenceType != null) {
                p = cb.and(p, cb.equal(root.get("evidenceType"), evidenceType));
            }
            if (officer != null && !officer.isBlank()) {
                // "officer": created it, currently holds it, or last touched it - any of the three counts as involvement.
                p = cb.and(p, cb.or(cb.equal(root.get("createdBy"), officer), cb.equal(root.get("currentCustodian"), officer)));
            }
            if (from != null) {
                p = cb.and(p, cb.greaterThanOrEqualTo(root.get("updatedAt"), from));
            }
            if (to != null) {
                p = cb.and(p, cb.lessThanOrEqualTo(root.get("updatedAt"), to));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase() + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("lastReason")), like),
                        cb.like(cb.lower(root.get("caseId")), like)));
            }
            return p;
        };
    }
}
