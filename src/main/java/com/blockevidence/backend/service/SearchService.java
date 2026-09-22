package com.blockevidence.backend.service;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.dto.EvidenceSearchResult;
import com.blockevidence.backend.dto.PageResponse;
import com.blockevidence.backend.sync.EvidenceProjection;
import com.blockevidence.backend.sync.EvidenceProjectionRepository;
import com.blockevidence.backend.sync.EvidenceProjectionSpecifications;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * H1 (design docs/G3_SYNC_DESIGN.md section 11): served from {@code evidence_projection} (G3's read model), not
 * the ledger - the whole point of G3 is that this never calls LedgerService.
 */
@Service
public class SearchService {

    private static final int MAX_PAGE_SIZE = 200;

    private final EvidenceProjectionRepository projections;

    public SearchService(EvidenceProjectionRepository projections) {
        this.projections = projections;
    }

    public PageResponse<EvidenceSearchResult> search(String caseId, EvidenceStatus status, EvidenceType type,
            String officer, Instant from, Instant to, String q, int page, int size, String sort) {
        var spec = EvidenceProjectionSpecifications.filter(caseId, status, type, officer, from, to, q);
        Sort.Direction direction = sort != null && sort.startsWith("-") ? Sort.Direction.DESC : Sort.Direction.ASC;
        String property = sortProperty(sort);
        var pageable = PageRequest.of(page, Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by(direction, property));
        var result = projections.findAll(spec, pageable);
        return PageResponse.of(result.map(SearchService::toResult));
    }

    /** An allow-list, not a raw pass-through: a sort key is a column name, and this keeps it to columns that
     *  actually exist, so a bad value cannot become a JPA property-resolution error (or, with a different ORM,
     *  something worse). */
    private static String sortProperty(String sort) {
        String key = sort == null ? "updatedAt" : sort.startsWith("-") ? sort.substring(1) : sort;
        return switch (key) {
            case "createdAt" -> "createdAt";
            case "status" -> "status";
            case "caseId" -> "caseId";
            default -> "updatedAt";
        };
    }

    private static EvidenceSearchResult toResult(EvidenceProjection p) {
        return new EvidenceSearchResult(p.getEvidenceId(), p.getCaseId(), p.getEvidenceType(), p.getStatus(),
                p.getVersion(), p.getCurrentCustodian(), p.getCreatedBy(), p.getCreatedAt(), p.getUpdatedAt(),
                p.getLastAction().name(), p.getLastReason());
    }
}
