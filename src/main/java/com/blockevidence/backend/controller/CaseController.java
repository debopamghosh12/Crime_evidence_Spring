package com.blockevidence.backend.controller;

import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.dto.AddMemberRequest;
import com.blockevidence.backend.dto.CaseResponse;
import com.blockevidence.backend.dto.CreateCaseRequest;
import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.UpdateCaseRequest;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Permissions;
import com.blockevidence.backend.service.CaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * E1/E2. Any authenticated user can read cases (case-level access, A5, is not scheduled); ADMIN and PROSECUTOR manage them.
 * The only DELETE here removes a team MEMBERSHIP, never a case and never evidence (C-02).
 */
@RestController
@RequestMapping("/api/cases")
@Tag(name = "Cases", description = "Case files and their team. Adding/removing a member also re-wraps/"
        + "revokes their content-key access to every evidence item already linked to the case (F3).")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Permissions.MANAGE_CASES)
    @Operation(summary = "Create a case", description = "Role: ADMIN or PROSECUTOR. The caller becomes "
            + "`createdBy`; `leadOfficerId` names a separate user (must hold a role that can lead a case) who "
            + "becomes its first team member with role LEAD_OFFICER.")
    public CaseResponse create(@Valid @RequestBody CreateCaseRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return caseService.create(request, user);
    }

    @GetMapping
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "List cases", description = "Any authenticated role (case-level read restriction, "
            + "A5, is not built - every case is visible to every authenticated user today).")
    public List<CaseResponse> list() {
        return caseService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Get a case", description = "Any authenticated role.")
    public CaseResponse get(@PathVariable UUID id) {
        return caseService.get(id);
    }

    @GetMapping("/{id}/evidence")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Evidence linked to this case", description = "Any authenticated role (E3). The full "
            + "ledger record for every item linked to this case - `CaseResponse.evidenceIds` has just the ids; "
            + "this resolves each one, so the caller's own wrapped content key (F2/F3) decides whether each "
            + "item's metadata comes back decrypted, same as GET /api/evidence/{id}.")
    public List<EvidenceResponse> evidence(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return caseService.evidence(id, user);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.MANAGE_CASES)
    @Operation(summary = "Update a case", description = "Role: ADMIN or PROSECUTOR. Changing "
            + "`leadOfficerId` demotes the previous lead to INVESTIGATOR rather than removing them.")
    public CaseResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCaseRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return caseService.update(id, request, user);
    }

    @PostMapping("/{id}/members")
    @PreAuthorize(Permissions.MANAGE_CASES)
    @Operation(summary = "Add a team member", description = """
            Role: ADMIN or PROSECUTOR. `caseRole` cannot be LEAD_OFFICER (set that through the case's own \
            `leadOfficerId` instead). F3: the new member's content key is re-wrapped (best-effort) for every \
            evidence item already linked to this case, recovering each item's key via any existing holder's \
            own wrapped copy - no cooperation needed from them, and nothing is re-encrypted.""")
    public CaseResponse addMember(@PathVariable UUID id, @Valid @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return caseService.addMember(id, request, user);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize(Permissions.MANAGE_CASES)
    @Operation(summary = "Remove a team member", description = """
            Role: ADMIN or PROSECUTOR. The lead officer cannot be removed this way (assign a different lead \
            first). F3: the removed member's wrapped content key is deleted for every evidence item linked to \
            this case - NOT retroactive (content they already decrypted before this point is unaffected, and \
            the content key itself is not rotated, a stated limit).""")
    public CaseResponse removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        return caseService.removeMember(id, userId);
    }
}
