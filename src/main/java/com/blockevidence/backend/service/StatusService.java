package com.blockevidence.backend.service;

import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.StatusChangeRequest;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.ledger.LedgerActor;
import com.blockevidence.backend.ledger.LedgerErrorCode;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.ledger.LedgerException;
import com.blockevidence.backend.ledger.LedgerService;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * D1: the status state machine. The transition table lives in {@link EvidenceStatus#canMoveTo}; it is checked HERE first, so a bad
 * jump is refused with a clear message before anything reaches the ledger, and AGAIN inside the chaincode (D1 requires both).
 * Called by StatusController.
 */
@Service
public class StatusService {

    private final LedgerService ledger;
    private final EvidenceService evidenceService;

    public StatusService(LedgerService ledger, EvidenceService evidenceService) {
        this.ledger = ledger;
        this.evidenceService = evidenceService;
    }

    public EvidenceResponse change(String evidenceId, StatusChangeRequest request, AuthenticatedUser user) {
        LedgerEvidenceRecord current = ledger.getEvidence(evidenceId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Evidence " + evidenceId + " does not exist"));
        if (current.version() != request.expectedVersion()) {
            throw new LedgerException(LedgerErrorCode.VERSION_CONFLICT, "Expected version " + request.expectedVersion()
                    + " but the record is at version " + current.version());
        }
        if (request.status() == EvidenceStatus.DISPOSED) {
            throw new LedgerException(LedgerErrorCode.INVALID_ARGUMENT,
                    "DISPOSED is only reachable through an approved disposal (B5)");
        }
        if (!current.status().canMoveTo(request.status())) {
            throw new LedgerException(LedgerErrorCode.INVALID_STATE,
                    "Status cannot move from " + current.status() + " to " + request.status());
        }
        ledger.updateStatus(evidenceId, request.expectedVersion(), request.status(), request.reason(),
                new LedgerActor(user.userId().toString(), user.role()));
        return evidenceService.get(evidenceId, false);
    }
}
