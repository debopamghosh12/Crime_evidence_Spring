package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.config.UploadProperties;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.dto.CustodyTimelineResponse;
import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.RegisterEvidenceRequest;
import com.blockevidence.backend.dto.StatusChangeRequest;
import com.blockevidence.backend.dto.TransferDecisionBody;
import com.blockevidence.backend.dto.TransferRequest;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.ledger.InMemoryLedgerService;
import com.blockevidence.backend.ledger.LedgerErrorCode;
import com.blockevidence.backend.ledger.LedgerException;
import com.blockevidence.backend.model.CaseFile;
import com.blockevidence.backend.model.User;
import com.blockevidence.backend.repository.CaseEvidenceLinkRepository;
import com.blockevidence.backend.repository.CaseFileRepository;
import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.support.FakeContentKeys;
import com.blockevidence.backend.support.FakeIpfsClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** D1-D3 through the services, on the in-memory ledger and a fake IPFS node. Case validation (E3) is stubbed to
 * always resolve, since these tests are about status/custody, not cases (that is CaseServiceTest/EvidenceServiceTest). */
class LifecycleServicesTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final FakeIpfsClient ipfs = new FakeIpfsClient();
    final InMemoryLedgerService ledger = new InMemoryLedgerService(clock);
    final UserRepository users = mock(UserRepository.class);
    final CaseFileRepository cases = mock(CaseFileRepository.class);
    final CaseEvidenceLinkRepository caseLinks = mock(CaseEvidenceLinkRepository.class);
    final com.blockevidence.backend.repository.CaseMemberRepository caseMembers =
            mock(com.blockevidence.backend.repository.CaseMemberRepository.class);
    final com.blockevidence.backend.notification.NotificationService notifications = mock(com.blockevidence.backend.notification.NotificationService.class);
    // F2/F3: a REAL content-key service - this file's assertions never touch EvidenceResponse.metadata(), so no
    // case-membership wiring is needed (a non-registrant actor simply sees metadataAvailable=false, degrade not
    // fail, exactly like an IPFS outage - see EvidenceServiceTest for the file that DOES test metadata content).
    final com.blockevidence.backend.crypto.UserKeyService userKeys = FakeContentKeys.userKeyService(clock);
    final com.blockevidence.backend.crypto.ContentKeyService contentKeys = FakeContentKeys.contentKeyService(userKeys, clock);

    AuthenticatedUser user(Role role) {
        AuthenticatedUser u = new AuthenticatedUser(UUID.randomUUID(), role.name().toLowerCase() + "@example.org", role);
        userKeys.provision(u.userId());
        return u;
    }

    final AuthenticatedUser collector = user(Role.COLLECTOR);
    final AuthenticatedUser analyst = user(Role.FORENSIC_ANALYST);
    final AuthenticatedUser prosecutor = user(Role.PROSECUTOR);
    final AuthenticatedUser judge = user(Role.JUDGE);
    final AuthenticatedUser auditor = user(Role.AUDITOR);

    final EvidenceService evidence = buildEvidenceService();
    final StatusService status = new StatusService(ledger, evidence);
    final CustodyService custody = new CustodyService(ledger, users, evidence);

    EvidenceService buildEvidenceService() {
        CaseFile existingCase = new CaseFile("ANY-CASE", "t", null, UUID.randomUUID(), UUID.randomUUID(), clock.instant());
        ReflectionTestUtils.setField(existingCase, "id", UUID.randomUUID());
        lenient().when(cases.findByCaseNumberIgnoreCase(anyString())).thenReturn(Optional.of(existingCase));
        lenient().when(caseMembers.findByCaseIdOrderByAddedAtAsc(any())).thenReturn(List.of());
        return new EvidenceService(ledger, ipfs, new VerificationService(ipfs, clock), JsonMapper.builder().build(),
                new UploadProperties(List.of("text/plain")), cases, caseLinks, caseMembers, notifications, contentKeys, clock);
    }

    /** Makes the repository know a user (as the receiver lookup needs). */
    User known(AuthenticatedUser a, boolean enabled) {
        User u = new User(a.email(), "hash", "Name", "Dept", a.role(), clock.instant());
        ReflectionTestUtils.setField(u, "id", a.userId());
        ReflectionTestUtils.setField(u, "enabled", enabled);
        when(users.findById(a.userId())).thenReturn(Optional.of(u));
        return u;
    }

    EvidenceResponse registered() {
        return evidence.register(new RegisterEvidenceRequest("CASE-1", EvidenceType.PHYSICAL, "Knife", null, null, null), null, collector);
    }

    TransferRequest to(AuthenticatedUser receiver, int version) {
        return new TransferRequest(version, receiver.userId(), "handover for analysis", "sealed bag 7");
    }

    // ------------------------------------------------------------------------------------------ D1

    @Test
    void statusMovesForwardOneStepAtATimeAndEachStepIsRecordedWithItsReason() {
        EvidenceResponse e = registered();
        var r1 = status.change(e.evidenceId(), new StatusChangeRequest(1, EvidenceStatus.PROCESSING, "sent to lab"), analyst);
        var r2 = status.change(e.evidenceId(), new StatusChangeRequest(2, EvidenceStatus.ANALYZED, "report done"), analyst);

        assertThat(r1.status()).isEqualTo(EvidenceStatus.PROCESSING);
        assertThat(r2.status()).isEqualTo(EvidenceStatus.ANALYZED);
        assertThat(r2.version()).isEqualTo(3);
        assertThat(r2.lastAction()).isEqualTo("STATUS_CHANGED");
        assertThat(r2.lastReason()).isEqualTo("report done");
    }

    @Test
    void invalidJumpsAreRefusedByTheServiceBeforeTheLedgerIsAsked() {
        EvidenceResponse e = registered();

        assertThatThrownBy(() -> status.change(e.evidenceId(), new StatusChangeRequest(1, EvidenceStatus.RELEASED, "skip"), analyst))
                .isInstanceOfSatisfying(LedgerException.class, x -> {
                    assertThat(x.ledgerCode()).isEqualTo(LedgerErrorCode.INVALID_STATE);
                    assertThat(x.getMessage()).contains("COLLECTED").contains("RELEASED");
                });
        assertThatThrownBy(() -> status.change(e.evidenceId(), new StatusChangeRequest(1, EvidenceStatus.COLLECTED, "same"), analyst))
                .isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> status.change(e.evidenceId(), new StatusChangeRequest(1, EvidenceStatus.DISPOSED, "shortcut"), analyst))
                .isInstanceOfSatisfying(LedgerException.class, x -> assertThat(x.ledgerCode()).isEqualTo(LedgerErrorCode.INVALID_ARGUMENT));
        assertThat(ledger.getHistory(e.evidenceId())).as("nothing was written").hasSize(1);
    }

    @Test
    void theLedgerEnforcesTheSameRulesEvenIfTheServiceCheckWereBypassed() {
        EvidenceResponse e = registered();
        // straight to the ledger, skipping StatusService: the second line of defence (D1 says "service AND chaincode")
        assertThatThrownBy(() -> ledger.updateStatus(e.evidenceId(), 1, EvidenceStatus.RELEASED, "skip",
                new com.blockevidence.backend.ledger.LedgerActor(analyst.userId().toString(), Role.FORENSIC_ANALYST)))
                .isInstanceOfSatisfying(LedgerException.class, x -> assertThat(x.ledgerCode()).isEqualTo(LedgerErrorCode.INVALID_STATE));
    }

    @Test
    void staleVersionUnknownIdAndWrongRoleAreRefused() {
        EvidenceResponse e = registered();
        assertThatThrownBy(() -> status.change(e.evidenceId(), new StatusChangeRequest(5, EvidenceStatus.PROCESSING, "r"), analyst))
                .isInstanceOfSatisfying(LedgerException.class, x -> assertThat(x.ledgerCode()).isEqualTo(LedgerErrorCode.VERSION_CONFLICT));
        assertThatThrownBy(() -> status.change("EV-" + UUID.randomUUID(), new StatusChangeRequest(1, EvidenceStatus.PROCESSING, "r"), analyst))
                .isInstanceOfSatisfying(ApiException.class, x -> assertThat(x.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> status.change(e.evidenceId(), new StatusChangeRequest(1, EvidenceStatus.PROCESSING, "r"), judge))
                .isInstanceOfSatisfying(LedgerException.class, x -> assertThat(x.ledgerCode()).isEqualTo(LedgerErrorCode.FORBIDDEN_ROLE));
    }

    // ------------------------------------------------------------------------------------------ D2

    @Test
    void aTwoStepTransferMovesCustodyOnlyWhenTheReceiverAccepts() {
        known(analyst, true);
        EvidenceResponse e = registered();

        EvidenceResponse pending = custody.initiate(e.evidenceId(), to(analyst, 1), collector);
        assertThat(pending.currentCustodian()).as("still the sender").isEqualTo(collector.userId().toString());

        assertThat(custody.pendingFor(analyst)).hasSize(1).first().satisfies(p -> {
            assertThat(p.evidenceId()).isEqualTo(e.evidenceId());
            assertThat(p.fromUser()).isEqualTo(collector.userId().toString());
            assertThat(p.reason()).isEqualTo("handover for analysis");
            assertThat(p.notes()).isEqualTo("sealed bag 7");
            assertThat(p.version()).isEqualTo(2);
        });
        assertThat(custody.pendingFor(collector)).as("the sender has nothing to answer").isEmpty();

        EvidenceResponse accepted = custody.accept(e.evidenceId(), new TransferDecisionBody(2, "received intact"), analyst);
        assertThat(accepted.currentCustodian()).isEqualTo(analyst.userId().toString());
        assertThat(custody.pendingFor(analyst)).isEmpty();
    }

    @Test
    void rejectAndCancelLeaveCustodyWithTheSender() {
        known(analyst, true);
        EvidenceResponse e = registered();
        custody.initiate(e.evidenceId(), to(analyst, 1), collector);
        assertThat(custody.reject(e.evidenceId(), new TransferDecisionBody(2, "not mine"), analyst).currentCustodian())
                .isEqualTo(collector.userId().toString());

        custody.initiate(e.evidenceId(), to(analyst, 3), collector);
        assertThat(custody.cancel(e.evidenceId(), new TransferDecisionBody(4, null), collector).currentCustodian())
                .isEqualTo(collector.userId().toString());
    }

    @Test
    void theReceiverMustExistBeActiveAndBeAbleToHoldEvidence() {
        known(auditor, true);
        User inactive = known(prosecutor, false);
        EvidenceResponse e = registered();

        assertThatThrownBy(() -> custody.initiate(e.evidenceId(), to(user(Role.JUDGE), 1), collector))
                .isInstanceOfSatisfying(ApiException.class, x -> assertThat(x.getCode()).isEqualTo("RECEIVER_NOT_FOUND"));
        assertThatThrownBy(() -> custody.initiate(e.evidenceId(), to(prosecutor, 1), collector))
                .as("inactive user %s", inactive.getEmail())
                .isInstanceOfSatisfying(ApiException.class, x -> assertThat(x.getCode()).isEqualTo("RECEIVER_NOT_FOUND"));
        assertThatThrownBy(() -> custody.initiate(e.evidenceId(), to(auditor, 1), collector))
                .isInstanceOfSatisfying(ApiException.class, x -> {
                    assertThat(x.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(x.getCode()).isEqualTo("RECEIVER_CANNOT_HOLD_EVIDENCE");
                });
        assertThat(ledger.getHistory(e.evidenceId())).as("nothing reached the ledger").hasSize(1);
    }

    @Test
    void theSenderIsAlwaysTheAuthenticatedUserSoANonCustodianCannotTransfer() {
        known(analyst, true);
        known(prosecutor, true);
        EvidenceResponse e = registered();   // custodian: collector

        assertThatThrownBy(() -> custody.initiate(e.evidenceId(), to(prosecutor, 1), analyst))
                .isInstanceOfSatisfying(LedgerException.class, x -> assertThat(x.ledgerCode()).isEqualTo(LedgerErrorCode.FORBIDDEN_ROLE));
    }

    // ------------------------------------------------------------------------------------------ D3

    @Test
    void theTimelineListsOnlyCustodyStepsInOrderWithLedgerTransactionsAndWhoHeldItAfterEach() {
        known(analyst, true);
        known(prosecutor, true);
        EvidenceResponse e = registered();
        status.change(e.evidenceId(), new StatusChangeRequest(1, EvidenceStatus.PROCESSING, "to lab"), collector);   // v2: not custody
        custody.initiate(e.evidenceId(), to(analyst, 2), collector);                                                   // v3
        custody.accept(e.evidenceId(), new TransferDecisionBody(3, "received intact"), analyst);                       // v4
        custody.initiate(e.evidenceId(), to(prosecutor, 4), analyst);                                                  // v5 (pending)

        CustodyTimelineResponse t = custody.timeline(e.evidenceId());

        assertThat(t.currentCustodian()).isEqualTo(analyst.userId().toString());
        assertThat(t.pendingTransfer()).isNotNull();
        assertThat(t.pendingTransfer().to()).isEqualTo(prosecutor.userId().toString());
        assertThat(t.events()).extracting(CustodyTimelineResponse.CustodyEvent::type)
                .containsExactly("CUSTODY_STARTED", "TRANSFER_INITIATED", "TRANSFER_ACCEPTED", "TRANSFER_INITIATED");
        assertThat(t.events()).extracting(CustodyTimelineResponse.CustodyEvent::version).containsExactly(1, 3, 4, 5);
        assertThat(t.events()).extracting(CustodyTimelineResponse.CustodyEvent::custodianAfter).containsExactly(
                collector.userId().toString(), collector.userId().toString(), analyst.userId().toString(), analyst.userId().toString());
        assertThat(t.events()).extracting(CustodyTimelineResponse.CustodyEvent::txId).allMatch(x -> x.length() == 64).doesNotHaveDuplicates();
        var accepted = t.events().get(2);
        assertThat(accepted.fromUser()).isEqualTo(collector.userId().toString());
        assertThat(accepted.toUser()).isEqualTo(analyst.userId().toString());
        assertThat(accepted.reason()).isEqualTo("handover for analysis");
        assertThat(accepted.resolutionNote()).isEqualTo("received intact");
        assertThat(accepted.actorId()).isEqualTo(analyst.userId().toString());
        assertThat(t.events().get(1).notes()).isEqualTo("sealed bag 7");
    }

    @Test
    void aFreshItemsTimelineIsJustItsRegistrant() {
        EvidenceResponse e = registered();
        CustodyTimelineResponse t = custody.timeline(e.evidenceId());

        assertThat(t.events()).hasSize(1);
        assertThat(t.events().get(0).type()).isEqualTo("CUSTODY_STARTED");
        assertThat(t.events().get(0).toUser()).isEqualTo(collector.userId().toString());
        assertThat(t.pendingTransfer()).isNull();
    }

    @Test
    void timelineOfAnUnknownItemIs404() {
        assertThatThrownBy(() -> custody.timeline("EV-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(LedgerException.class, x -> assertThat(x.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(StandardCharsets.UTF_8).isNotNull();
    }
}
