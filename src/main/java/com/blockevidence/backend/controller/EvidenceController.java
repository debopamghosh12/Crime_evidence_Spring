package com.blockevidence.backend.controller;

import java.util.List;

import com.blockevidence.backend.dto.DisposalDecisionBody;
import com.blockevidence.backend.dto.DisposalRequestBody;
import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.HistoryEntryResponse;
import com.blockevidence.backend.dto.RegisterEvidenceRequest;
import com.blockevidence.backend.dto.UpdateEvidenceRequest;
import com.blockevidence.backend.dto.VerificationResponse;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Permissions;
import com.blockevidence.backend.service.EvidenceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * B1-B5, C2, C3. Binding, validation and the first role check (Permissions) only; the logic is in
 * EvidenceService.
 *
 * <p>There is intentionally NO DELETE mapping (constraint C-02). B5 replaces delete with the disposal
 * request/approve/reject flow; a DELETE on these paths answers 405.
 *
 * <p>The acting user always comes from {@code @AuthenticationPrincipal}, i.e. from the verified JWT (C-05).
 * Evidence ids are constrained by the path pattern so anything else is a plain 404.
 */
@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private static final String ID = "{id:EV-[0-9a-fA-F-]{36}}";

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    /** multipart/form-data: JSON part "metadata" and, for DIGITAL evidence, a binary part "file". */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Permissions.REGISTER_OR_UPDATE_EVIDENCE)
    public EvidenceResponse register(@RequestPart("metadata") @Valid RegisterEvidenceRequest metadata,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.register(metadata, file, user);
    }

    /** {@code verify=true} also re-hashes the stored content (C2); the default does not, it is expensive. */
    @GetMapping("/" + ID)
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public EvidenceResponse get(@PathVariable String id, @RequestParam(defaultValue = "false") boolean verify) {
        return evidenceService.get(id, verify);
    }

    @GetMapping("/by-cid/{cid}")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public List<EvidenceResponse> byCid(@PathVariable String cid) {
        return evidenceService.findByCid(cid);
    }

    @GetMapping("/" + ID + "/versions/{version}")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public EvidenceResponse version(@PathVariable String id, @PathVariable int version) {
        return evidenceService.getVersion(id, version);
    }

    @GetMapping("/" + ID + "/history")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public List<HistoryEntryResponse> history(@PathVariable String id) {
        return evidenceService.history(id);
    }

    @GetMapping("/" + ID + "/verify")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public VerificationResponse verify(@PathVariable String id) {
        return evidenceService.verify(id);
    }

    @PutMapping("/" + ID)
    @PreAuthorize(Permissions.REGISTER_OR_UPDATE_EVIDENCE)
    public EvidenceResponse update(@PathVariable String id, @Valid @RequestBody UpdateEvidenceRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.update(id, request, user);
    }

    @PostMapping("/" + ID + "/disposal")
    @PreAuthorize(Permissions.REQUEST_DISPOSAL)
    public EvidenceResponse requestDisposal(@PathVariable String id, @Valid @RequestBody DisposalRequestBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.requestDisposal(id, body, user);
    }

    @PostMapping("/" + ID + "/disposal/approve")
    @PreAuthorize(Permissions.DECIDE_DISPOSAL)
    public EvidenceResponse approveDisposal(@PathVariable String id, @Valid @RequestBody DisposalDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.approveDisposal(id, body, user);
    }

    @PostMapping("/" + ID + "/disposal/reject")
    @PreAuthorize(Permissions.DECIDE_DISPOSAL)
    public EvidenceResponse rejectDisposal(@PathVariable String id, @Valid @RequestBody DisposalDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.rejectDisposal(id, body, user);
    }
}
