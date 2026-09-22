package com.blockevidence.backend.service;

import com.blockevidence.backend.dto.ActivityEntryResponse;
import com.blockevidence.backend.dto.PageResponse;
import com.blockevidence.backend.sync.EvidenceActivity;
import com.blockevidence.backend.sync.EvidenceActivityRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * H3 (design docs/G3_SYNC_DESIGN.md section 11): recent actions, from evidence_activity (G3), ordered by
 * ledger_at DESC. Not filtered to the requesting user's cases (A5 case-level restriction is still not built,
 * same as every other read in this project, docs/KNOWN_GAPS.md) - {@code caseId} is an optional narrowing filter
 * the CALLER supplies, not an access restriction.
 */
@Service
public class ActivityService {

    private static final int MAX_PAGE_SIZE = 200;

    private final EvidenceActivityRepository activities;

    public ActivityService(EvidenceActivityRepository activities) {
        this.activities = activities;
    }

    public PageResponse<ActivityEntryResponse> feed(String caseId, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        var result = (caseId == null || caseId.isBlank())
                ? activities.findAllByOrderByLedgerAtDesc(pageable)
                : activities.findByCaseIdOrderByLedgerAtDesc(caseId, pageable);
        return PageResponse.of(result.map(ActivityService::toResponse));
    }

    private static ActivityEntryResponse toResponse(EvidenceActivity a) {
        return new ActivityEntryResponse(a.getTxId(), a.getEvidenceId(), a.getCaseId(), a.getVersion(),
                a.getAction().name(), a.getActorId(), a.getActorRole(), a.getReason(), a.getLedgerAt());
    }
}
