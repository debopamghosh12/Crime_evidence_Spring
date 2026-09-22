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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Custody & Status", description = "The status state machine and the two-step custody transfer "
        + "(initiate by the sender, then the named receiver accepts/rejects, or the sender cancels while it "
        + "is still pending). Custody does NOT move on initiate - only on accept.")
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
    @Operation(summary = "Change status", description = """
            Role: COLLECTOR, FORENSIC_ANALYST or PROSECUTOR (D1) - any of the three may move any item along \
            the legal line (COLLECTED -> PROCESSING -> ANALYZED -> ARCHIVED -> RELEASED); this is not \
            restricted to the current custodian (a documented, accepted gap - KNOWN_GAPS). A move must follow \
            the state machine exactly - a skip or a step backwards is 409 INVALID_STATE. DISPOSED cannot be \
            reached here at all - only through the disposal request/approve flow below, because it needs a \
            JUDGE's sign-off. `reason` is mandatory; `expectedVersion` is optimistic concurrency (a mismatch \
            is 409 VERSION_CONFLICT).""")
    public EvidenceResponse changeStatus(@PathVariable String id, @Valid @RequestBody StatusChangeRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return statusService.change(id, request, user);
    }

    @PostMapping("/api/evidence/" + ID + "/transfers")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    @Operation(summary = "Initiate a custody transfer (step 1 of 2)", description = """
            Role: COLLECTOR, FORENSIC_ANALYST, PROSECUTOR or JUDGE (any role that can hold evidence) - and \
            must be the CURRENT CUSTODIAN. `toUserId` names the receiver (their role is looked up server-side \
            and must be one that can hold evidence too - AUDITOR and ADMIN never can). Custody does NOT move \
            yet: the record gains a PENDING transfer and stays with the sender until the receiver accepts \
            below. Only one transfer can be pending at a time, and none while a disposal request is pending. \
            `reason` is mandatory, `notes` optional.""")
    public EvidenceResponse initiate(@PathVariable String id, @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.initiate(id, request, user);
    }

    @PostMapping("/api/evidence/" + ID + "/transfers/accept")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    @Operation(summary = "Accept a pending transfer (step 2 of 2)", description = "Must be the NAMED "
            + "RECEIVER of the pending transfer, not just any custody-capable role. This is the only step "
            + "that actually moves custody - `currentCustodian` becomes the caller. `note` is optional.")
    public EvidenceResponse accept(@PathVariable String id, @Valid @RequestBody TransferDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.accept(id, body, user);
    }

    @PostMapping("/api/evidence/" + ID + "/transfers/reject")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    @Operation(summary = "Reject a pending transfer", description = "Must be the named receiver. Clears the "
            + "PENDING transfer; custody stays with the original sender.")
    public EvidenceResponse reject(@PathVariable String id, @Valid @RequestBody TransferDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.reject(id, body, user);
    }

    @PostMapping("/api/evidence/" + ID + "/transfers/cancel")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    @Operation(summary = "Cancel a pending transfer", description = "Must be the original SENDER, not the "
            + "receiver. Withdraws the PENDING transfer before the receiver has responded; custody is "
            + "unaffected (it never moved).")
    public EvidenceResponse cancel(@PathVariable String id, @Valid @RequestBody TransferDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.cancel(id, body, user);
    }

    @GetMapping("/api/transfers/pending")
    @PreAuthorize(Permissions.HOLD_CUSTODY)
    @Operation(summary = "My pending transfers", description = "Every transfer currently waiting for the "
            + "CALLER to respond as the named receiver (D2) - never another user's.")
    public List<PendingTransferResponse> pending(@AuthenticationPrincipal AuthenticatedUser user) {
        return custodyService.pendingFor(user);
    }

    @GetMapping("/api/evidence/" + ID + "/chain-of-custody")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Custody timeline", description = "Any authenticated role (D3). Built from the "
            + "ledger's full history, filtered to the events that concern custody specifically: creation "
            + "(custody starts with the registrant) and every transfer initiation/acceptance/rejection/"
            + "cancellation, each with its real ledger timestamp and transaction id.")
    public CustodyTimelineResponse timeline(@PathVariable String id) {
        return custodyService.timeline(id);
    }
}
