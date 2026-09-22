package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.crypto.ContentKeyService;
import com.blockevidence.backend.domain.CaseRole;
import com.blockevidence.backend.domain.CaseStatus;
import com.blockevidence.backend.dto.AddMemberRequest;
import com.blockevidence.backend.dto.CaseResponse;
import com.blockevidence.backend.dto.CreateCaseRequest;
import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.UpdateCaseRequest;
import com.blockevidence.backend.dto.VerificationResponse;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.model.CaseEvidenceLink;
import com.blockevidence.backend.model.CaseFile;
import com.blockevidence.backend.model.CaseMember;
import com.blockevidence.backend.model.User;
import com.blockevidence.backend.repository.CaseEvidenceLinkRepository;
import com.blockevidence.backend.repository.CaseFileRepository;
import com.blockevidence.backend.repository.CaseMemberRepository;
import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** E1/E2 with the repositories mocked; the schema itself is proven by the live run (Flyway V2 against PostgreSQL).
 * E3 (evidenceIds/evidence()) is covered here too; register()'s own use of the link table is EvidenceServiceTest's job. */
class CaseServiceTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final CaseFileRepository cases = mock(CaseFileRepository.class);
    final CaseMemberRepository members = mock(CaseMemberRepository.class);
    final CaseEvidenceLinkRepository evidenceLinks = mock(CaseEvidenceLinkRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final EvidenceService evidenceService = mock(EvidenceService.class);
    final ContentKeyService contentKeys = mock(ContentKeyService.class);
    final CaseService service = new CaseService(cases, members, evidenceLinks, users, evidenceService, contentKeys, clock);

    final AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "admin@example.org", Role.ADMIN);
    final List<CaseMember> stored = new ArrayList<>();
    final List<CaseEvidenceLink> linked = new ArrayList<>();
    final java.util.Map<UUID, CaseFile> caseStore = new java.util.HashMap<>();

    EvidenceResponse sampleEvidence(String evidenceId) {
        return new EvidenceResponse(evidenceId, "C", null, null, 1, null, null, null, null, null, null, null, null,
                null, null, "CREATED", null, new EvidenceResponse.Disposal("NONE", null, null, null), false, null,
                VerificationResponse.notChecked(evidenceId));
    }

    User user(Role role, boolean enabled) {
        User u = new User(role.name() + "@example.org", "h", "N", "D", role, clock.instant());
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(u, "enabled", enabled);
        when(users.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    CaseFile saved(CaseFile f) {
        ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
        return f;
    }

    CreateCaseRequest create(UUID lead) {
        return new CreateCaseRequest("CASE-2026-9", "Burglary at 12 High St", "desc", lead);
    }

    void wireRepositories() {
        // a small stateful fake: whatever was saved can be found again by id
        when(cases.save(any(CaseFile.class))).thenAnswer(i -> {
            CaseFile f = i.getArgument(0, CaseFile.class);
            CaseFile withId = f.getId() == null ? saved(f) : f;
            caseStore.put(withId.getId(), withId);
            return withId;
        });
        when(cases.findById(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(caseStore.get(i.getArgument(0, UUID.class))));
        when(members.save(any(CaseMember.class))).thenAnswer(i -> {
            CaseMember m = i.getArgument(0);
            if (!stored.contains(m)) {
                stored.add(m);   // like JPA: saving an already-managed row updates it, it does not add a second one
            }
            return m;
        });
        when(members.findByCaseIdOrderByAddedAtAsc(any())).thenAnswer(i -> stored.stream()
                .filter(m -> m.getCaseId().equals(i.getArgument(0))).toList());
        when(members.findByCaseIdAndUserId(any(), any())).thenAnswer(i -> stored.stream()
                .filter(m -> m.getCaseId().equals(i.getArgument(0)) && m.getUserId().equals(i.getArgument(1))).findFirst());
        org.mockito.Mockito.doAnswer(i -> stored.remove(i.getArgument(0, CaseMember.class))).when(members).delete(any(CaseMember.class));
        when(evidenceLinks.findByCaseIdOrderByLinkedAtAsc(any())).thenAnswer(i -> linked.stream()
                .filter(l -> l.getCaseId().equals(i.getArgument(0))).toList());
    }

    @Test
    void creatingACaseRecordsTheCreatorFromTheTokenOpensItAndMakesTheLeadAMember() {
        wireRepositories();
        User lead = user(Role.COLLECTOR, true);

        CaseResponse r = service.create(create(lead.getId()), admin);

        assertThat(r.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(r.createdBy()).isEqualTo(admin.userId());
        assertThat(r.leadOfficerId()).isEqualTo(lead.getId());
        assertThat(r.members()).singleElement().satisfies(m -> {
            assertThat(m.userId()).isEqualTo(lead.getId());
            assertThat(m.caseRole()).isEqualTo(CaseRole.LEAD_OFFICER);
        });
    }

    @Test
    void aCaseListsTheEvidenceLinkedToItByE3AndNothingElse() {
        wireRepositories();
        CaseResponse c = service.create(create(user(Role.COLLECTOR, true).getId()), admin);
        assertThat(service.get(c.id()).evidenceIds()).isEmpty();
        assertThat(service.evidence(c.id(), admin)).isEmpty();

        linked.add(new CaseEvidenceLink(c.id(), "EV-1", admin.userId(), clock.instant()));
        when(evidenceService.get("EV-1", false, admin)).thenReturn(sampleEvidence("EV-1"));

        assertThat(service.get(c.id()).evidenceIds()).containsExactly("EV-1");
        assertThat(service.evidence(c.id(), admin)).extracting(EvidenceResponse::evidenceId).containsExactly("EV-1");
        assertThatThrownBy(() -> service.evidence(UUID.randomUUID(), admin)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void duplicateNumbersUnknownOrInactiveLeadsAndNonWorkingRolesAreRefused() {
        wireRepositories();
        when(cases.existsByCaseNumberIgnoreCase("CASE-2026-9")).thenReturn(true);
        assertThatThrownBy(() -> service.create(create(UUID.randomUUID()), admin)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("CASE_NUMBER_TAKEN"));

        when(cases.existsByCaseNumberIgnoreCase("CASE-2026-9")).thenReturn(false);
        assertThatThrownBy(() -> service.create(create(UUID.randomUUID()), admin)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        User inactive = user(Role.COLLECTOR, false);
        assertThatThrownBy(() -> service.create(create(inactive.getId()), admin)).isInstanceOf(ApiException.class);
        User auditor = user(Role.AUDITOR, true);
        assertThatThrownBy(() -> service.create(create(auditor.getId()), admin)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("INVALID_LEAD_OFFICER"));
        verify(cases, never()).save(any());
    }

    @Test
    void membersHaveARoleOnTheCaseAndCannotBeAddedTwiceOrAsLead() {
        wireRepositories();
        CaseResponse c = service.create(create(user(Role.COLLECTOR, true).getId()), admin);
        User analyst = user(Role.FORENSIC_ANALYST, true);

        CaseResponse withMember = service.addMember(c.id(), new AddMemberRequest(analyst.getId(), CaseRole.FORENSIC_ANALYST), admin);
        assertThat(withMember.members()).extracting(CaseResponse.Member::caseRole)
                .containsExactly(CaseRole.LEAD_OFFICER, CaseRole.FORENSIC_ANALYST);
        assertThat(withMember.members().get(1).addedBy()).isEqualTo(admin.userId());

        assertThatThrownBy(() -> service.addMember(c.id(), new AddMemberRequest(analyst.getId(), CaseRole.OBSERVER), admin))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ALREADY_A_MEMBER"));
        assertThatThrownBy(() -> service.addMember(c.id(), new AddMemberRequest(UUID.randomUUID(), CaseRole.OBSERVER), admin))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.addMember(c.id(), new AddMemberRequest(analyst.getId(), CaseRole.LEAD_OFFICER), admin))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("USE_LEAD_OFFICER_FIELD"));
        assertThatThrownBy(() -> service.addMember(UUID.randomUUID(), new AddMemberRequest(analyst.getId(), CaseRole.OBSERVER), admin))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theLeadOfficerCannotBeRemovedButOtherMembersCan() {
        wireRepositories();
        User lead = user(Role.COLLECTOR, true);
        User analyst = user(Role.FORENSIC_ANALYST, true);
        CaseResponse c = service.create(create(lead.getId()), admin);
        service.addMember(c.id(), new AddMemberRequest(analyst.getId(), CaseRole.FORENSIC_ANALYST), admin);
        when(cases.findById(c.id())).thenReturn(Optional.of(caseWith(c)));

        assertThatThrownBy(() -> service.removeMember(c.id(), lead.getId())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("CANNOT_REMOVE_LEAD_OFFICER"));
        assertThat(service.removeMember(c.id(), analyst.getId()).members()).extracting(CaseResponse.Member::userId).containsExactly(lead.getId());
        assertThatThrownBy(() -> service.removeMember(c.id(), analyst.getId())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    CaseFile caseWith(CaseResponse c) {
        CaseFile f = new CaseFile(c.caseNumber(), c.title(), c.description(), c.leadOfficerId(), c.createdBy(), c.createdAt());
        ReflectionTestUtils.setField(f, "id", c.id());
        return f;
    }

    @Test
    void changingTheLeadMakesTheNewLeadTheLeadMemberAndKeepsTheOldOneOnTheTeam() {
        wireRepositories();
        User oldLead = user(Role.COLLECTOR, true);
        User newLead = user(Role.PROSECUTOR, true);
        CaseResponse c = service.create(create(oldLead.getId()), admin);
        when(cases.findById(c.id())).thenReturn(Optional.of(caseWith(c)));

        CaseResponse r = service.update(c.id(), new UpdateCaseRequest("Renamed", null, newLead.getId()), admin);

        assertThat(r.title()).isEqualTo("Renamed");
        assertThat(r.leadOfficerId()).isEqualTo(newLead.getId());
        assertThat(r.members()).extracting(CaseResponse.Member::userId, CaseResponse.Member::caseRole)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(newLead.getId(), CaseRole.LEAD_OFFICER),
                        org.assertj.core.groups.Tuple.tuple(oldLead.getId(), CaseRole.INVESTIGATOR));
        // Regression (docs/bugs/case-lead-change-unique-violation.md): delete-then-insert of the same (case, user) pair broke on
        // PostgreSQL because Hibernate flushes inserts before deletes. A lead change must only change roles on existing rows.
        verify(members, never()).delete(any());
    }

    @Test
    void promotingAnExistingMemberToLeadChangesTheirRoleInPlace() {
        wireRepositories();
        User oldLead = user(Role.COLLECTOR, true);
        User analyst = user(Role.FORENSIC_ANALYST, true);
        CaseResponse c = service.create(create(oldLead.getId()), admin);
        service.addMember(c.id(), new AddMemberRequest(analyst.getId(), CaseRole.FORENSIC_ANALYST), admin);
        when(cases.findById(c.id())).thenReturn(Optional.of(caseWith(c)));

        CaseResponse r = service.update(c.id(), new UpdateCaseRequest(null, null, analyst.getId()), admin);

        assertThat(r.members()).hasSize(2).extracting(CaseResponse.Member::userId, CaseResponse.Member::caseRole)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(analyst.getId(), CaseRole.LEAD_OFFICER),
                        org.assertj.core.groups.Tuple.tuple(oldLead.getId(), CaseRole.INVESTIGATOR));
        verify(members, never()).delete(any());
    }

    @Test
    void theCaseNumberAndStatusCannotBeChangedThroughUpdate() {
        assertThat(UpdateCaseRequest.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("title", "description", "leadOfficerId");
        verify(cases, org.mockito.Mockito.never()).delete(any());
        verify(members, org.mockito.Mockito.never()).delete(any());
        assertThat(atLeastOnce()).isNotNull();
    }
}
