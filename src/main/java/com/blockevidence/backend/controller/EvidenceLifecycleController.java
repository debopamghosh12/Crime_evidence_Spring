package com.blockevidence.backend.controller;

import java.util.List;

import com.blockevidence.backend.dto.CustodyTimelineResponse;
import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.PendingTransferResponse;
import com.blockevidence.backend.dto.StatusChangeRequest;
import com.blockevidence.backend.dto.TransferDecisionBody;
import com.blockevidence.backend.dto.TransferRequest;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Permissions;
import com.blockevidence.backend.service.CustodyService;
import com.blockevidence.backend.service.StatusService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * D1-D3: status changes, custody transfer and the custody timeline. A separate controller from EvidenceController so the Phase 2
 * endpoints are untouched. There is still no DELETE anywhere (C-02). The sender and every responder are the authenticated
 * principal (C-05); {@code toUserId} in a transfer names the receiver, never the actor.
 */
@RestController
public class EvidenceLifecycleController {

    private static final String ID = "{id:EV-[0-9a-fA-F-]{36}}";

    private final StatusService statusService;
    private final CustodyService custodyService;

    public EvidenceLifecycleController(StatusService statusService, CustodyService custodyService) {
        this.statusService = statusService;
        this.custodyService = custodyService;
    }

    @PostMapping("/api/evidence/" + ID + "/status")
    @PreAuthorize(Permissions.CHANGE_STATUS)
    public EvidenceResponse changeStatus(@PathVariable String id, @Valid @RequestBody StatusChangeRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return statusService.change(id, request, user);
    }

    @PostMapping("/api/evidence/" + ID + "/transfers")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    public EvidenceResponse initiate(@PathVariable String id, @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.initiate(id, request, user);
    }

    @PostMapping("/api/evidence/" + ID + "/transfers/accept")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    public EvidenceResponse accept(@PathVariable String id, @Valid @RequestBody TransferDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.accept(id, body, user);
    }

    @PostMapping("/api/evidence/" + ID + "/transfers/reject")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    public EvidenceResponse reject(@PathVariable String id, @Valid @RequestBody TransferDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.reject(id, body, user);
    }

    @PostMapping("/api/evidence/" + ID + "/transfers/cancel")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    public EvidenceResponse cancel(@PathVariable String id, @Valid @RequestBody TransferDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.cancel(id, body, user);
    }

    /** Transfers waiting for the CURRENT user (D2: "pending transfers are listed for the receiver"). */
    @GetMapping("/api/transfers/pending")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    public List<PendingTransferResponse> pending(@AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.pendingFor(user);
    }

    @GetMapping("/api/evidence/" + ID + "/chain-of-custody")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public CustodyTimelineResponse timeline(@PathVariable String id) {
        return custodyService.timeline(id);
    }
}
