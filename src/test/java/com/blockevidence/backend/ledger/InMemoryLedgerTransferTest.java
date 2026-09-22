package com.blockevidence.backend.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.support.FakeIpfsClient;
import org.junit.jupiter.api.Test;

/** D2 in the Java executable spec. Scenario-for-scenario the same as chaincode/evidence/transfer_test.go. */
class InMemoryLedgerTransferTest {

    final InMemoryLedgerService ledger = new InMemoryLedgerService(Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC));
    final String cid = FakeIpfsClient.cidOf("m".getBytes());
    final String sha = "a".repeat(64);

    LedgerActor actor(Role role) {
        return new LedgerActor(UUID.randomUUID().toString(), role);
    }

    final LedgerActor collector = actor(Role.COLLECTOR);
    final LedgerActor analyst = actor(Role.FORENSIC_ANALYST);
    final LedgerActor prosecutor = actor(Role.PROSECUTOR);
    final LedgerActor judge = actor(Role.JUDGE);
    final LedgerActor auditor = actor(Role.AUDITOR);
    final LedgerActor admin = actor(Role.ADMIN);

    String created() {
        String id = "EV-" + UUID.randomUUID();
        ledger.createEvidence(new LedgerNewEvidence(id, "C", EvidenceType.PHYSICAL, cid, sha, null, null, null), collector);
        return id;
    }

    static LedgerErrorCode codeOf(Throwable t) {
        return ((LedgerException) t).ledgerCode();
    }

    void assertRefused(Runnable call, LedgerErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.ledgerCode()).isEqualTo(code));
    }

    LedgerTxResult initiate(String id, int v, LedgerActor to, String reason, LedgerActor from) {
        return ledger.initiateTransfer(id, v, to.userId(), to.role(), reason, "sealed bag", from);
    }

    @Test
    void onlyTheCurrentCustodianCanInitiateAndCustodyMovesOnlyOnAcceptance() {
        String id = created();
        assertRefused(() -> initiate(id, 1, analyst, "lab", analyst), LedgerErrorCode.FORBIDDEN_ROLE);   // not the custodian
        initiate(id, 1, analyst, "lab", collector);

        var r = ledger.getEvidence(id).orElseThrow();
        assertThat(r.transfer().state()).isEqualTo(LedgerEvidenceRecord.TransferState.PENDING);
        assertThat(r.transfer().from()).isEqualTo(collector.userId());
        assertThat(r.transfer().to()).isEqualTo(analyst.userId());
        assertThat(r.transfer().notes()).isEqualTo("sealed bag");
        assertThat(r.currentCustodian()).as("unchanged until accepted").isEqualTo(collector.userId());
        assertThat(r.lastAction()).isEqualTo(LedgerAction.TRANSFER_INITIATED);
        assertThat(r.version()).isEqualTo(2);
    }

    @Test
    void acceptMakesTheReceiverCustodianAndOnlyTheyCanHandOn() {
        String id = created();
        initiate(id, 1, analyst, "analysis", collector);
        ledger.acceptTransfer(id, 2, "received intact", analyst);

        var r = ledger.getEvidence(id).orElseThrow();
        assertThat(r.currentCustodian()).isEqualTo(analyst.userId());
        assertThat(r.transfer().state()).isEqualTo(LedgerEvidenceRecord.TransferState.ACCEPTED);
        assertThat(r.transfer().resolutionNote()).isEqualTo("received intact");
        assertThat(r.transfer().resolvedAt()).isNotNull();
        assertThat(r.createdBy()).as("the registrant is never rewritten").isEqualTo(collector.userId());
        assertRefused(() -> initiate(id, 3, prosecutor, "x", collector), LedgerErrorCode.FORBIDDEN_ROLE);  // the old custodian
        initiate(id, 3, prosecutor, "to prosecution", analyst);                                          // the new custodian
    }

    @Test
    void rejectKeepsCustodyAndAllowsANewTransferAndCancelIsForTheSenderOnly() {
        String id = created();
        initiate(id, 1, analyst, "r", collector);
        ledger.rejectTransfer(id, 2, "not mine", analyst);
        assertThat(ledger.getEvidence(id).orElseThrow().currentCustodian()).isEqualTo(collector.userId());

        initiate(id, 3, prosecutor, "r", collector);
        assertRefused(() -> ledger.cancelTransfer(id, 4, "n", prosecutor), LedgerErrorCode.FORBIDDEN_ROLE);  // receiver cannot cancel
        ledger.cancelTransfer(id, 4, "changed my mind", collector);
        assertThat(ledger.getEvidence(id).orElseThrow().transfer().state()).isEqualTo(LedgerEvidenceRecord.TransferState.CANCELLED);
        assertRefused(() -> ledger.cancelTransfer(id, 5, "n", collector), LedgerErrorCode.INVALID_STATE);      // nothing pending
    }

    @Test
    void onlyTheNamedReceiverWithTheAddressedRoleCanRespond() {
        String id = created();
        initiate(id, 1, analyst, "r", collector);

        assertRefused(() -> ledger.acceptTransfer(id, 2, "n", collector), LedgerErrorCode.FORBIDDEN_ROLE);   // the sender
        assertRefused(() -> ledger.acceptTransfer(id, 2, "n", prosecutor), LedgerErrorCode.FORBIDDEN_ROLE);  // a bystander
        assertRefused(() -> ledger.rejectTransfer(id, 2, "n", judge), LedgerErrorCode.FORBIDDEN_ROLE);
        var sameUserOtherRole = new LedgerActor(analyst.userId(), Role.PROSECUTOR);
        assertRefused(() -> ledger.acceptTransfer(id, 2, "n", sameUserOtherRole), LedgerErrorCode.FORBIDDEN_ROLE);
    }

    @Test
    void rolesThatCannotHoldEvidenceAreRefusedEverywhere() {
        String id = created();
        assertRefused(() -> initiate(id, 1, analyst, "r", auditor), LedgerErrorCode.FORBIDDEN_ROLE);
        assertRefused(() -> initiate(id, 1, analyst, "r", admin), LedgerErrorCode.FORBIDDEN_ROLE);
        assertRefused(() -> initiate(id, 1, auditor, "r", collector), LedgerErrorCode.INVALID_ARGUMENT);   // receiver cannot hold
        assertRefused(() -> initiate(id, 1, admin, "r", collector), LedgerErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void argumentAndStateChecks() {
        String id = created();
        assertRefused(() -> initiate(id, 1, collector, "r", collector), LedgerErrorCode.INVALID_ARGUMENT);    // to yourself
        assertRefused(() -> ledger.initiateTransfer(id, 1, "not-a-uuid", Role.FORENSIC_ANALYST, "r", "n", collector), LedgerErrorCode.INVALID_ARGUMENT);
        assertRefused(() -> initiate(id, 1, analyst, "  ", collector), LedgerErrorCode.INVALID_ARGUMENT);     // blank reason
        assertRefused(() -> initiate(id, 9, analyst, "r", collector), LedgerErrorCode.VERSION_CONFLICT);
        assertRefused(() -> ledger.initiateTransfer(id, 1, analyst.userId(), analyst.role(), "r", "x".repeat(1001), collector),
                LedgerErrorCode.INVALID_ARGUMENT);
        initiate(id, 1, analyst, "r", collector);
        assertRefused(() -> initiate(id, 2, prosecutor, "r", collector), LedgerErrorCode.INVALID_STATE);      // one at a time
    }

    @Test
    void noTransferWhileADisposalIsPendingOrAfterDisposal() {
        String id = created();
        ledger.requestDisposal(id, 1, "why", collector);
        assertRefused(() -> initiate(id, 2, analyst, "r", collector), LedgerErrorCode.INVALID_STATE);
        ledger.approveDisposal(id, 2, "ok", judge);
        assertRefused(() -> initiate(id, 3, analyst, "r", collector), LedgerErrorCode.INVALID_STATE);
    }

    @Test
    void pendingListHoldsOnlyWhatIsStillPendingTowardThatUser() {
        String a = created();
        String b = created();
        String c = created();
        initiate(a, 1, analyst, "r", collector);
        initiate(b, 1, analyst, "r", collector);
        initiate(c, 1, prosecutor, "r", collector);

        assertThat(ledger.findPendingTransferIds(analyst.userId())).containsExactlyInAnyOrder(a, b);
        ledger.acceptTransfer(a, 2, "n", analyst);
        ledger.cancelTransfer(b, 2, "n", collector);
        assertThat(ledger.findPendingTransferIds(analyst.userId())).isEmpty();
        assertThat(ledger.findPendingTransferIds(prosecutor.userId())).containsExactly(c);
        assertThat(ledger.findPendingTransferIds(judge.userId())).isEmpty();
        assertRefused(() -> ledger.findPendingTransferIds("nope"), LedgerErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void historyRecordsEveryStepSoTheTimelineCanBeRebuilt() {
        String id = created();
        initiate(id, 1, analyst, "lab", collector);
        ledger.acceptTransfer(id, 2, "n", analyst);
        initiate(id, 3, prosecutor, "court", analyst);
        ledger.rejectTransfer(id, 4, "n", prosecutor);

        List<LedgerAction> actions = ledger.getHistory(id).stream().map(e -> e.record().lastAction()).toList();
        assertThat(actions).containsExactly(LedgerAction.CREATED, LedgerAction.TRANSFER_INITIATED,
                LedgerAction.TRANSFER_ACCEPTED, LedgerAction.TRANSFER_INITIATED, LedgerAction.TRANSFER_REJECTED);
        assertThat(ledger.getHistory(id).get(4).record().currentCustodian()).isEqualTo(analyst.userId());
    }
}
