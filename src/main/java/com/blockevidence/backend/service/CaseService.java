package com.blockevidence.backend.service;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.blockevidence.backend.domain.CaseRole;
import com.blockevidence.backend.dto.AddMemberRequest;
import com.blockevidence.backend.dto.CaseResponse;
import com.blockevidence.backend.dto.CreateCaseRequest;
import com.blockevidence.backend.dto.UpdateCaseRequest;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.model.CaseFile;
import com.blockevidence.backend.model.CaseMember;
import com.blockevidence.backend.model.User;
import com.blockevidence.backend.repository.CaseFileRepository;
import com.blockevidence.backend.repository.CaseMemberRepository;
import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E1/E2: cases and their team. Called by CaseController. Entirely off-chain (C-06): the ledger only ever sees the case NUMBER as a
 * plain string on evidence records.
 *
 * <p>Removing a team member deletes one membership row. That is not evidence, so C-02 (never delete evidence records) does not apply;
 * this is the only delete in the codebase and it is deliberately narrow: the lead officer cannot be removed. Changing the lead officer
 * never deletes anything; it changes roles in place.
 */
@Service
public class CaseService {

    /** Who may lead a case: roles that work evidence. */
    static final Set<Role> CAN_LEAD = EnumSet.of(Role.COLLECTOR, Role.FORENSIC_ANALYST, Role.PROSECUTOR);

    private final CaseFileRepository cases;
    private final CaseMemberRepository members;
    private final UserRepository users;
    private final Clock clock;

    public CaseService(CaseFileRepository cases, CaseMemberRepository members, UserRepository users, Clock clock) {
        this.cases = cases;
        this.members = members;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public CaseResponse create(CreateCaseRequest request, AuthenticatedUser creator) {
        if (cases.existsByCaseNumberIgnoreCase(request.caseNumber())) {
            throw new ApiException(HttpStatus.CONFLICT, "CASE_NUMBER_TAKEN", "A case with this number already exists");
        }
        User lead = requireLeadOfficer(request.leadOfficerId());
        CaseFile saved = cases.save(new CaseFile(request.caseNumber(), request.title(), request.description(), lead.getId(),
                creator.userId(), clock.instant()));
        members.save(new CaseMember(saved.getId(), lead.getId(), CaseRole.LEAD_OFFICER, creator.userId(), clock.instant()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public CaseResponse get(UUID caseId) {
        return toResponse(load(caseId));
    }

    @Transactional(readOnly = true)
    public List<CaseResponse> list() {
        return cases.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public CaseResponse update(UUID caseId, UpdateCaseRequest request, AuthenticatedUser actor) {
        CaseFile file = load(caseId);
        UUID newLead = null;
        if (request.leadOfficerId() != null && !request.leadOfficerId().equals(file.getLeadOfficerId())) {
            newLead = requireLeadOfficer(request.leadOfficerId()).getId();
        }
        file.update(request.title(), request.description(), newLead);
        final UUID incomingLead = newLead;
        if (incomingLead != null) {
            // The new lead becomes LEAD_OFFICER; the previous lead stays on the team as an INVESTIGATOR. Both are role changes on
            // existing rows (the unique key is case+user, and Hibernate flushes inserts before deletes, so delete-then-insert of
            // the same pair violates it: docs/bugs/case-lead-change-unique-violation.md).
            members.findByCaseIdOrderByAddedAtAsc(caseId).stream()
                    .filter(m -> m.getCaseRole() == CaseRole.LEAD_OFFICER)
                    .forEach(m -> {
                        m.changeRole(CaseRole.INVESTIGATOR);
                        members.save(m);
                    });
            members.findByCaseIdAndUserId(caseId, incomingLead).ifPresentOrElse(m -> {
                m.changeRole(CaseRole.LEAD_OFFICER);
                members.save(m);
            }, () -> members.save(new CaseMember(caseId, incomingLead, CaseRole.LEAD_OFFICER, actor.userId(), clock.instant())));
        }
        return toResponse(cases.save(file));
    }

    @Transactional
    public CaseResponse addMember(UUID caseId, AddMemberRequest request, AuthenticatedUser actor) {
        CaseFile file = load(caseId);
        if (request.caseRole() == CaseRole.LEAD_OFFICER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USE_LEAD_OFFICER_FIELD",
                    "The lead officer is set through the case's leadOfficerId, not as a member role");
        }
        User user = users.findById(request.userId()).filter(User::isEnabled).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist or is inactive"));
        if (members.findByCaseIdAndUserId(caseId, user.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_A_MEMBER", "This user is already on the case team");
        }
        members.save(new CaseMember(caseId, user.getId(), request.caseRole(), actor.userId(), clock.instant()));
        return toResponse(file);
    }

    @Transactional
    public CaseResponse removeMember(UUID caseId, UUID userId) {
        CaseFile file = load(caseId);
        if (userId.equals(file.getLeadOfficerId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_REMOVE_LEAD_OFFICER",
                    "The lead officer cannot be removed; assign a different lead officer first");
        }
        CaseMember member = members.findByCaseIdAndUserId(caseId, userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "That user is not on this case team"));
        members.delete(member);
        return toResponse(file);
    }

    private User requireLeadOfficer(UUID userId) {
        User lead = users.findById(userId).filter(User::isEnabled).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The lead officer does not exist or is inactive"));
        if (!CAN_LEAD.contains(lead.getRole())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LEAD_OFFICER",
                    "A user with role " + lead.getRole() + " cannot lead a case");
        }
        return lead;
    }

    private CaseFile load(UUID caseId) {
        return cases.findById(caseId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Case " + caseId + " does not exist"));
    }

    private CaseResponse toResponse(CaseFile c) {
        List<CaseResponse.Member> team = members.findByCaseIdOrderByAddedAtAsc(c.getId()).stream()
                .map(m -> new CaseResponse.Member(m.getUserId(), m.getCaseRole(), m.getAddedBy(), m.getAddedAt())).toList();
        return new CaseResponse(c.getId(), c.getCaseNumber(), c.getTitle(), c.getDescription(), c.getLeadOfficerId(),
                c.getStatus(), c.getCreatedBy(), c.getCreatedAt(), team);
    }
}
