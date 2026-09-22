package com.blockevidence.backend.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.blockevidence.backend.dto.CustodyTimelineResponse;
import com.blockevidence.backend.dto.CustodyTimelineResponse.CustodyEvent;
import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.PendingTransferResponse;
import com.blockevidence.backend.dto.TransferDecisionBody;
import com.blockevidence.backend.dto.TransferRequest;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.ledger.LedgerAction;
import com.blockevidence.backend.ledger.LedgerActor;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.ledger.LedgerHistoryEntry;
import com.blockevidence.backend.ledger.LedgerService;
import com.blockevidence.backend.model.User;
import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * D2/D3: two-step custody transfer and the custody timeline. Called by CustodyController.
 *
 * <p>The SENDER and every responder always come from the JWT (C-05). The RECEIVER is a request field, looked up here so the
 * ledger is given their role (the ledger cannot look users up; until A2 the role is an argument, constraint C-08).
 */
@Service
public class CustodyService {

    /** Roles that can hold evidence. Mirrors the chaincode's canHoldCustody and InMemoryLedgerService.CAN_HOLD_CUSTODY. */
    static final Set<Role> CAN_HOLD = EnumSet.of(Role.COLLECTOR, Role.FORENSIC_ANALYST, Role.PROSECUTOR, Role.JUDGE);

    private final LedgerService ledger;
    private final UserRepository users;
    private final EvidenceService evidenceService;

    public CustodyService(LedgerService ledger, UserRepository users, EvidenceService evidenceService) {
        this.ledger = ledger;
        this.users = users;
        this.evidenceService = evidenceService;
    }

    public EvidenceResponse initiate(String evidenceId, TransferRequest request, AuthenticatedUser sender) {
        User receiver = users.findById(request.toUserId()).filter(User::isEnabled).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "RECEIVER_NOT_FOUND", "The receiving user does not exist or is inactive"));
        if (!CAN_HOLD.contains(receiver.getRole())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECEIVER_CANNOT_HOLD_EVIDENCE",
                    "A user with role " + receiver.getRole() + " cannot hold custody of evidence");
        }
        ledger.initiateTransfer(evidenceId, request.expectedVersion(), receiver.getId().toString(), receiver.getRole(),
                request.reason(), request.notes(), actor(sender));
        return evidenceService.get(evidenceId, false, sender);
    }

    public EvidenceResponse accept(String evidenceId, TransferDecisionBody body, AuthenticatedUser user) {
        ledger.acceptTransfer(evidenceId, body.expectedVersion(), body.note(), actor(user));
        return evidenceService.get(evidenceId, false, user);
    }

    public EvidenceResponse reject(String evidenceId, TransferDecisionBody body, AuthenticatedUser user) {
        ledger.rejectTransfer(evidenceId, body.expectedVersion(), body.note(), actor(user));
        return evidenceService.get(evidenceId, false, user);
    }

    public EvidenceResponse cancel(String evidenceId, TransferDecisionBody body, AuthenticatedUser user) {
        ledger.cancelTransfer(evidenceId, body.expectedVersion(), body.note(), actor(user));
        return evidenceService.get(evidenceId, false, user);
    }

    /** D2: everything waiting for THIS user to respond. */
    public List<PendingTransferResponse> pendingFor(AuthenticatedUser user) {
        List<PendingTransferResponse> pending = new ArrayList<>();
        for (String id : ledger.findPendingTransferIds(user.userId().toString())) {
            ledger.getEvidence(id).filter(r -> r.transfer() != null).ifPresent(r -> pending.add(
                    new PendingTransferResponse(r.evidenceId(), r.caseId(), r.status(), r.version(), r.transfer().from(),
                            r.transfer().reason(), r.transfer().notes(), r.transfer().initiatedAt())));
        }
        return pending;
    }

    /**
     * D3: the custody timeline, built from the ledger's history. Only steps that concern custody are listed: the creation (custody
     * starts with the registrant) and every transfer initiation, acceptance, rejection and cancellation.
     */
    public CustodyTimelineResponse timeline(String evidenceId) {
        List<LedgerHistoryEntry> history = ledger.getHistory(evidenceId);
        List<CustodyEvent> events = new ArrayList<>();
        for (LedgerHistoryEntry entry : history) {
            LedgerEvidenceRecord r = entry.record();
            LedgerAction action = r.lastAction();
            var t = r.transfer();
            switch (action) {
                case CREATED -> events.add(new CustodyEvent(r.version(), entry.txId(), entry.timestamp(), "CUSTODY_STARTED",
                        null, r.createdBy(), null, null, null, r.updatedBy(), r.updatedByRole(), r.currentCustodian()));
                case TRANSFER_INITIATED, TRANSFER_ACCEPTED, TRANSFER_REJECTED, TRANSFER_CANCELLED -> {
                    if (t != null) {
                        events.add(new CustodyEvent(r.version(), entry.txId(), entry.timestamp(), action.name(), t.from(),
                                t.to(), t.reason(), t.notes(), action == LedgerAction.TRANSFER_INITIATED ? null : t.resolutionNote(),
                                r.updatedBy(), r.updatedByRole(), r.currentCustodian()));
                    }
                }
                default -> {
                    // status changes, metadata updates and disposal steps do not change custody
                }
            }
        }
        LedgerEvidenceRecord latest = history.get(history.size() - 1).record();
        CustodyTimelineResponse.PendingTransfer pending = latest.transfer() != null
                && latest.transfer().state() == LedgerEvidenceRecord.TransferState.PENDING
                        ? new CustodyTimelineResponse.PendingTransfer(latest.transfer().from(), latest.transfer().to(),
                                latest.transfer().reason(), latest.transfer().notes(), latest.transfer().initiatedAt())
                        : null;
        return new CustodyTimelineResponse(evidenceId, latest.currentCustodian(), pending, events);
    }

    private static LedgerActor actor(AuthenticatedUser user) {
        return new LedgerActor(user.userId().toString(), user.role());
    }
}
