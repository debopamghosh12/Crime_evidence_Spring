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
import com.blockevidence.backend.service.ReportService;
import com.blockevidence.backend.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Evidence", description = "Register, read, update, verify and dispose of evidence. Content "
        + "(the file and its metadata document) is AES-256-GCM encrypted per item and RSA-wrapped per "
        + "authorised user (F2/F3) - reading it back or downloading it needs a wrapped key; verifying its "
        + "integrity or generating a chain-of-custody report does not (see each endpoint below).")
public class EvidenceController {

    private static final String ID = "{id:EV-[0-9a-fA-F-]{36}}";

    private final EvidenceService evidenceService;
    private final AuditService audit;
    private final SearchService searchService;
    private final ReportService reportService;

    public EvidenceController(EvidenceService evidenceService, AuditService audit, SearchService searchService,
            ReportService reportService) {
        this.evidenceService = evidenceService;
        this.audit = audit;
        this.searchService = searchService;
        this.reportService = reportService;
    }

    @GetMapping("/search")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Search evidence", description = """
            Any authenticated role. Served from the G3 off-chain read model (evidence_projection), never the \
            ledger directly. `officer` matches either the creator or the current custodian. `q` is a \
            case-insensitive substring match over the case number and the CURRENT reason only (an earlier \
            version's reason is not searched - the projection is a current-state table by design). `sort` is \
            an allow-list (createdAt/status/caseId/updatedAt, default updatedAt) - an unrecognised value falls \
            back to updatedAt rather than erroring. `size` is capped at 200.""")
    public PageResponse<EvidenceSearchResult> search(@RequestParam(required = false) String caseId,
            @RequestParam(required = false) EvidenceStatus status, @RequestParam(required = false) EvidenceType type,
            @RequestParam(required = false) String officer, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return searchService.search(caseId, status, type, officer, from, to, q, page, size, sort);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Permissions.REGISTER_OR_UPDATE_EVIDENCE)
    @Operation(summary = "Register evidence", description = """
            Role: COLLECTOR or FORENSIC_ANALYST. Multipart request: a JSON part named "metadata" \
            (RegisterEvidenceRequest) plus, for DIGITAL evidence only, a binary part named "file" (PHYSICAL \
            evidence must NOT include one). The registering user comes only from the verified token (C-05) - \
            no field for it exists in the request body, so nothing a client sends can override who registered \
            it. The case named by `caseId` must already exist (E3).

            **F2/F3 (automatic, nothing to configure in the request):** a fresh AES-256 content key is \
            generated for this item, the file and metadata document are encrypted with it before being pinned \
            to IPFS, and that key is RSA-wrapped for the registrant (mandatory) and every current member of \
            the named case (best-effort). The ledger's recorded hash is of the CIPHERTEXT that was pinned, not \
            the plaintext. A registrant with no provisioned keypair fails the whole call (nothing is created \
            that nobody could ever read).

            Order of operations: hash-while-streaming the file to IPFS, then the metadata document, then the \
            ledger write last - if the ledger write fails, whatever was pinned is unpinned again (unless \
            another record already references that exact CID, F5/D-024).""")
    @ApiResponse(responseCode = "201", description = "Registered")
    @ApiResponse(responseCode = "400", description = "DIGITAL with no file, PHYSICAL with a file, or a "
            + "validation failure", content = @Content)
    @ApiResponse(responseCode = "404", description = "No case with that caseId exists yet", content = @Content)
    @ApiResponse(responseCode = "415", description = "The file's content type is not on the allow-list",
            content = @Content)
    public EvidenceResponse register(@RequestPart("metadata") @Valid RegisterEvidenceRequest metadata,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.register(metadata, file, user);
    }

    @GetMapping("/" + ID)
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Get evidence", description = """
            Any authenticated role. `verify=true` also re-fetches and re-hashes the stored content against the \
            ledger's recorded hash (expensive; the default does not) and is audited as a DOWNLOAD, not a VIEW \
            (A6). The `metadata` object (description/location/notes/file info) is decrypted with the CALLER's \
            own wrapped content key; a caller with no wrapped key for this item still gets every ledger field \
            (status, hashes, custodian, timestamps...) but `metadataAvailable: false` and `metadata: null` - \
            the same degrade-not-fail shape an IPFS outage produces, not an error.""")
    public EvidenceResponse get(@PathVariable String id, @RequestParam(defaultValue = "false") boolean verify,
            @AuthenticationPrincipal AuthenticatedUser user) {
        EvidenceResponse response = evidenceService.get(id, verify, user);
        audit.recordAccess(id, verify);
        return response;
    }

    @GetMapping("/by-cid/{cid}")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Find evidence by CID", description = "Any authenticated role. Every evidence item "
            + "that ever referenced this file or metadata CID (several can share one, e.g. the same file "
            + "registered twice) - same decrypt-with-caller's-key behaviour as GET /{id}.")
    public List<EvidenceResponse> byCid(@PathVariable String cid, @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.findByCid(cid, user);
    }

    @GetMapping("/" + ID + "/versions/{version}")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Get one historical version", description = "Any authenticated role (B4). Every "
            + "earlier version stays readable with the metadata document THAT version pointed at - nothing is "
            + "ever overwritten or deleted (C-02).")
    public EvidenceResponse version(@PathVariable String id, @PathVariable int version,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.getVersion(id, version, user);
    }

    @GetMapping("/" + ID + "/history")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Full ledger history", description = "Any authenticated role (C3). Every committed "
            + "version, oldest first, with each one's real Fabric transaction id, ledger timestamp, action, "
            + "acting user/role and reason - ledger-native fields only, no content decryption involved.")
    public List<HistoryEntryResponse> history(@PathVariable String id) {
        return evidenceService.history(id);
    }

    @GetMapping("/" + ID + "/verify")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Verify integrity", description = """
            Any authenticated role - deliberately needs NO content key. Re-fetches the file and metadata \
            document from IPFS and re-hashes them against the ledger's recorded hash (which covers the \
            CIPHERTEXT, F2/F3 design section 7); a mismatch means TAMPERED, missing content means NOT_FOUND, \
            otherwise VERIFIED (a proven mismatch always outranks missing content). This is why a Judge or \
            Auditor who was never wrapped in for this item's content can still prove it has not been tampered \
            with - the same property the chain-of-custody report (below) relies on. Always audited as a \
            DOWNLOAD (A6): the actual bytes are fetched.""")
    public VerificationResponse verify(@PathVariable String id) {
        VerificationResponse response = evidenceService.verify(id);
        audit.recordAccess(id, true);
        return response;
    }

    @GetMapping("/" + ID + "/report")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Chain-of-custody PDF report", description = """
            Any authenticated role - like verify(), deliberately needs NO content key (I1). Built ENTIRELY \
            from the ledger and a fresh verify() result: evidence details (case, type, status, custodian, \
            timestamps - the ledger-native record, never the off-chain free-text description, which is the \
            content F2/F3 protects), the full timeline, every real transaction id, the recorded hashes, and \
            the verification result. A Judge or Auditor never wrapped in for this item's content generates the \
            exact same report as one who was - proven live, not just asserted (docs/features/\
            i1-chain-of-custody-report.md). Response is `application/pdf`, `Content-Disposition: attachment`. \
            Always audited as a DOWNLOAD.""")
    @ApiResponse(responseCode = "200", description = "The PDF", content = @Content(mediaType = "application/pdf"))
    @ApiResponse(responseCode = "404", description = "Unknown evidence id", content = @Content)
    public void report(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletResponse response) throws IOException {
        byte[] pdf = reportService.generateChainOfCustodyReport(id, user);
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + id + "-chain-of-custody.pdf\"");
        response.setContentLength(pdf.length);
        response.getOutputStream().write(pdf);
        audit.recordAccess(id, true);
    }

    @GetMapping("/" + ID + "/file")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Download the decrypted file", description = """
            Any authenticated role IN PRINCIPLE, but this is the one read that DOES need content access \
            (F2/F3 Q3): the caller's own wrapped content key decrypts the file, streamed straight from IPFS. \
            A caller with no wrapped key for this item (never a case member, never the registrant, never a \
            custody-transfer receiver) gets 403 KEY_NOT_AUTHORISED - not a 404, so the difference between \
            "does not exist" and "exists but you cannot read it" stays honest. PHYSICAL evidence (no file at \
            all) answers 404 NO_FILE. Always audited as a DOWNLOAD.""")
    @ApiResponse(responseCode = "200", description = "The decrypted file, original content-type/filename")
    @ApiResponse(responseCode = "403", description = "KEY_NOT_AUTHORISED - no wrapped content key for this "
            + "caller on this item", content = @Content)
    @ApiResponse(responseCode = "404", description = "Unknown evidence id, or PHYSICAL evidence (no file)",
            content = @Content)
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
    @Operation(summary = "Update metadata (new version)", description = """
            Role: COLLECTOR or FORENSIC_ANALYST, AND the caller must hold a wrapped content key for this item \
            (F2/F3 side effect: updating needs the key to re-encrypt the new metadata version - a 403 \
            KEY_NOT_AUTHORISED for a caller with none, independent of the role check above). `expectedVersion` \
            is optimistic concurrency (B4) - a mismatch is 409 VERSION_CONFLICT. Only description/location/\
            notes can change; file fields are immutable. The current metadata is hash-checked against the \
            ledger before this builds on it, so tampered content can never be laundered into a fresh version.""")
    @ApiResponse(responseCode = "200", description = "The new version")
    @ApiResponse(responseCode = "403", description = "Wrong role, or no wrapped content key for this item",
            content = @Content)
    @ApiResponse(responseCode = "409", description = "VERSION_CONFLICT (stale expectedVersion) or "
            + "INTEGRITY_CHECK_FAILED (stored metadata does not match the ledger hash)", content = @Content)
    public EvidenceResponse update(@PathVariable String id, @Valid @RequestBody UpdateEvidenceRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.update(id, request, user);
    }

    @PostMapping("/" + ID + "/disposal")
    @PreAuthorize(Permissions.REQUEST_DISPOSAL)
    @Operation(summary = "Request disposal (step 1 of 2)", description = """
            Role: COLLECTOR or PROSECUTOR (B5). Nothing is removed - the record only gains a PENDING disposal \
            request; status is unchanged until a JUDGE approves it below. `reason` is mandatory. This is the \
            ONLY path to the DISPOSED status - a plain status-change call can never set it directly.""")
    public EvidenceResponse requestDisposal(@PathVariable String id, @Valid @RequestBody DisposalRequestBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.requestDisposal(id, body, user);
    }

    @PostMapping("/" + ID + "/disposal/approve")
    @PreAuthorize(Permissions.DECIDE_DISPOSAL)
    @Operation(summary = "Approve disposal (step 2 of 2)", description = """
            Role: JUDGE only. Status becomes DISPOSED and the record freezes there (still readable, still \
            verifiable, still on the ledger forever - C-02, "dispose" is never "delete"). `note` is optional.""")
    public EvidenceResponse approveDisposal(@PathVariable String id, @Valid @RequestBody DisposalDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.approveDisposal(id, body, user);
    }

    @PostMapping("/" + ID + "/disposal/reject")
    @PreAuthorize(Permissions.DECIDE_DISPOSAL)
    @Operation(summary = "Reject disposal", description = "Role: JUDGE only. Clears the PENDING request; "
            + "status and custody are otherwise unchanged. `note` is optional.")
    public EvidenceResponse rejectDisposal(@PathVariable String id, @Valid @RequestBody DisposalDecisionBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return evidenceService.rejectDisposal(id, body, user);
    }
}
