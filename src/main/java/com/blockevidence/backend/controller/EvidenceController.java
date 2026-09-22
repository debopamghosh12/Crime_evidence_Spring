package com.blockevidence.backend.controller;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import com.blockevidence.backend.audit.AuditService;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.dto.DisposalDecisionBody;
import com.blockevidence.backend.dto.DisposalRequestBody;
import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.EvidenceSearchResult;
import com.blockevidence.backend.dto.HistoryEntryResponse;
import com.blockevidence.backend.dto.PageResponse;
import com.blockevidence.backend.dto.RegisterEvidenceRequest;
import com.blockevidence.backend.dto.UpdateEvidenceRequest;
import com.blockevidence.backend.dto.VerificationResponse;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Permissions;
import com.blockevidence.backend.service.EvidenceService;
import com.blockevidence.backend.service.SearchService;
import jakarta.servlet.http.HttpServletResponse;
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
    private final AuditService audit;
    private final SearchService searchService;

    public EvidenceController(EvidenceService evidenceService, AuditService audit, SearchService searchService) {
        this.evidenceService = evidenceService;
        this.audit = audit;
        this.searchService = searchService;
    }

    /** H1: caseId/status/type/officer/date-range/free-text filters, served from the G3 read model, not the ledger. */
    @GetMapping("/search")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public PageResponse<EvidenceSearchResult> search(@RequestParam(required = false) String caseId,
            @RequestParam(required = false) EvidenceStatus status, @RequestParam(required = false) EvidenceType type,
            @RequestParam(required = false) String officer, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return searchService.search(caseId, status, type, officer, from, to, q, page, size, sort);
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

    /**
     * {@code verify=true} also re-hashes the stored content (C2); the default does not, it is expensive.
     * A6: {@code verify=true} means the server fetched and re-hashed the actual file bytes, so it is audited
     * as a DOWNLOAD; the default (metadata/ledger fields only) is a VIEW.
     */
    @GetMapping("/" + ID)
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public EvidenceResponse get(@PathVariable String id, @RequestParam(defaultValue = "false") boolean verify,
            @AuthenticationPrincipal AuthenticatedUser user) {
        EvidenceResponse response = evidenceService.get(id, verify, user);
        audit.recordAccess(id, verify);
        return response;
    }

    @GetMapping("/by-cid/{cid}")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public List<EvidenceResponse> byCid(@PathVariable String cid, @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.findByCid(cid, user);
    }

    @GetMapping("/" + ID + "/versions/{version}")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public EvidenceResponse version(@PathVariable String id, @PathVariable int version,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.getVersion(id, version, user);
    }

    @GetMapping("/" + ID + "/history")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public List<HistoryEntryResponse> history(@PathVariable String id) {
        return evidenceService.history(id);
    }

    /** A6: always re-fetches and re-hashes the file, so this is always a DOWNLOAD. */
    @GetMapping("/" + ID + "/verify")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public VerificationResponse verify(@PathVariable String id) {
        VerificationResponse response = evidenceService.verify(id);
        audit.recordAccess(id, true);
        return response;
    }

    /**
     * F2 Q3: decrypts and streams the file to an authorised caller only - 403 KEY_NOT_AUTHORISED if the caller
     * has no wrapped content key for this item (docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md section 6). Always a
     * DOWNLOAD (A6): the actual bytes are fetched and decrypted, not just ledger/metadata fields.
     */
    @GetMapping("/" + ID + "/file")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public void file(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletResponse response) throws IOException {
        evidenceService.streamFile(id, user, descriptor -> {
            response.setContentType(descriptor.contentType() != null ? descriptor.contentType()
                    : MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + descriptor.fileName().replace("\"", "'") + "\"");
        }, response.getOutputStream());
        audit.recordAccess(id, true);
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
