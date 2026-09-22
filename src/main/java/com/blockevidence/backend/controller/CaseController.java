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
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Permissions.MANAGE_CASES)
    public CaseResponse create(@Valid @RequestBody CreateCaseRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return caseService.create(request, user);
    }

    @GetMapping
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public List<CaseResponse> list() {
        return caseService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public CaseResponse get(@PathVariable UUID id) {
        return caseService.get(id);
    }

    /** E3: the full evidence records linked to this case (CaseResponse.evidenceIds has just the ids). */
    @GetMapping("/{id}/evidence")
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public List<EvidenceResponse> evidence(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return caseService.evidence(id, user);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.MANAGE_CASES)
    public CaseResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCaseRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return caseService.update(id, request, user);
    }

    @PostMapping("/{id}/members")
    @PreAuthorize(Permissions.MANAGE_CASES)
    public CaseResponse addMember(@PathVariable UUID id, @Valid @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return caseService.addMember(id, request, user);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize(Permissions.MANAGE_CASES)
    public CaseResponse removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        return caseService.removeMember(id, userId);
    }
}
